#include "core/OrderBook.h"
#include <algorithm>
#include <chrono>
#include <sstream>
#include <iostream>
#include <random>
#include <thread>

namespace matcher {
namespace core {

using namespace matcher::model;

// 用于生成唯一交易ID的计数器
namespace {
    std::atomic<int64_t> tradeCounter{0};
    std::mt19937_64 rng(std::chrono::steady_clock::now().time_since_epoch().count());
    
    std::string generateTradeId() {
        // 使用atomic counter确保线程安全，添加更多随机性
        int64_t counter = ++tradeCounter;
        auto now = std::chrono::steady_clock::now();
        auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch()).count();
        // 添加更多随机性和唯一性保证
        uint64_t random_component = rng();
        std::ostringstream oss;
        oss << "T" << ns << "_" << counter << "_" << random_component;
        return oss.str();
    }
}

std::vector<Trade> OrderBook::submit(const Order& order) {
    std::lock_guard<std::mutex> lock(mutex_);
    // 验证订单
    if (order.qty <= 0) {
        std::cerr << "订单数量非法: " << order.clOrderId << " qty=" << order.qty << std::endl;
        return {};
    }
    if (order.price <= 0) {
        std::cerr << "订单价格非法: " << order.clOrderId << " price=" << order.price << std::endl;
        return {};
    }
    if (order.side != "B" && order.side != "S") {
        std::cerr << "订单方向非法: " << order.clOrderId << " side=" << order.side << std::endl;
        return {};
    }
    
    // Copy order to modify local qty
    Order o = order;
    return matchAgainst(std::move(o));
}

void OrderBook::addOrder(const Order& order) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (order.qty <= 0) {
        std::cerr << "订单数量非法，无法添加到订单簿: " << order.clOrderId << std::endl;
        return;
    }

    // 添加到对应的价格队列
    if (order.side == "B") {
        buyOrders_[order.price].push_back(order);
    } else if (order.side == "S") {
        sellOrders_[order.price].push_back(order);
    } else {
        std::cerr << "订单方向非法: " << order.side << std::endl;
    }
}


bool OrderBook::removeOrder(const Order& order) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto remove_from_map = [&](auto& m) -> bool {
        for (auto it = m.begin(); it != m.end(); ++it) {
            auto& vec = it->second;
            auto vit = std::find_if(vec.begin(), vec.end(), 
                                   [&](const Order& o){ return o.clOrderId == order.clOrderId; });
            if (vit != vec.end()) {
                vec.erase(vit);
                if (vec.empty()) m.erase(it);
                return true;
            }
        }
        return false;
    };

    if (order.side == "B") {
        return remove_from_map(buyOrders_);
    } else if (order.side == "S") {
        return remove_from_map(sellOrders_);
    }
    return false;
}

bool OrderBook::cancel(const std::string& clOrderId) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto remove_from_map = [&](auto& m) -> bool {
        for (auto it = m.begin(); it != m.end(); ++it) {
            auto& vec = it->second;
            auto vit = std::find_if(vec.begin(), vec.end(), [&](const Order& o){ return o.clOrderId == clOrderId; });
            if (vit != vec.end()) {
                vec.erase(vit);
                if (vec.empty()) m.erase(it);
                return true;
            }
        }
        return false;
    };

    if (remove_from_map(buyOrders_)) return true;
    if (remove_from_map(sellOrders_)) return true;
    return false;
}

std::vector<Order> OrderBook::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Order> res;
    for (const auto& [price, vec] : buyOrders_) {
        res.insert(res.end(), vec.begin(), vec.end());
    }
    for (const auto& [price, vec] : sellOrders_) {
        res.insert(res.end(), vec.begin(), vec.end());
    }
    return res;
}

std::vector<Trade> OrderBook::matchAgainst(Order order) {
    std::vector<Trade> trades;

    bool isBuy = (order.side == "B");

    // 简单限价单撮合逻辑：价格-时间优先原则
    if (isBuy) {
        // 匹配最低价格的卖单（卖单价格升序排列）
        for (auto sit = sellOrders_.begin(); sit != sellOrders_.end() && order.qty > 0; ) {
            double sellPrice = sit->first;
            if (order.price < sellPrice) break; // 买单出价低于卖单价，无法撮合

            auto& vec = sit->second;
            for (auto vit = vec.begin(); vit != vec.end() && order.qty > 0; ) {
                Order& sellOrder = *vit;
                // 使用int类型进行撮合，避免类型转换问题
                int execQty = std::min(order.qty, sellOrder.qty);
                if (execQty <= 0) { 
                    ++vit; 
                    continue; 
                }

                // 创建成交记录
                auto now = std::chrono::system_clock::now();
                Trade t;
                t.tradeId = generateTradeId();
                t.clOrderIdBuy = order.clOrderId;
                t.clOrderIdSell = sellOrder.clOrderId;
                t.securityId = order.securityId;
                t.price = sellOrder.price;
                t.quantity = execQty;
                t.timestamp = now;

                trades.push_back(t);

                // 更新成交数量
                order.qty -= execQty;
                sellOrder.qty -= execQty;

                if (sellOrder.qty == 0) {
                    vit = vec.erase(vit);
                } else {
                    ++vit;
                }
            }

            if (vec.empty()) {
                sit = sellOrders_.erase(sit);
            } else {
                ++sit;
            }
        }

        // 如果还有剩余，添加到买单簿
        if (order.qty > 0) {
            buyOrders_[order.price].push_back(order);
        }
    } else {
        // 卖单：匹配最高价格的买单（买单价格降序排列）
        for (auto bit = buyOrders_.begin(); bit != buyOrders_.end() && order.qty > 0; ) {
            double buyPrice = bit->first;
            if (order.price > buyPrice) break; // 卖单要价高于买单价，无法撮合

            auto& vec = bit->second;
            for (auto vit = vec.begin(); vit != vec.end() && order.qty > 0; ) {
                Order& buyOrder = *vit;
                int execQty = std::min(order.qty, buyOrder.qty);
                if (execQty <= 0) { 
                    ++vit; 
                    continue; 
                }

                auto now = std::chrono::system_clock::now();
                Trade t;
                t.tradeId = generateTradeId();
                t.clOrderIdBuy = buyOrder.clOrderId;
                t.clOrderIdSell = order.clOrderId;
                t.securityId = order.securityId;
                t.price = buyOrder.price;
                t.quantity = execQty;
                t.timestamp = now;

                trades.push_back(t);

                order.qty -= execQty;
                buyOrder.qty -= execQty;

                if (buyOrder.qty == 0) {
                    vit = vec.erase(vit);
                } else {
                    ++vit;
                }
            }

            if (vec.empty()) {
                bit = buyOrders_.erase(bit);
            } else {
                ++bit;
            }
        }

        if (order.qty > 0) {
            sellOrders_[order.price].push_back(order);
        }
    }

    return trades;
}

std::vector<Trade> OrderBook::matchOrder(matcher::model::Order& order) {
    // 使用submit方法进行撮合，但我们需要返回交易记录
    // submit方法会复制订单，所以我们需要处理原始订单的更新
    Order orderCopy = order;
    auto trades = submit(orderCopy);
    
    // 更新原始订单的数量（撮合后的剩余数量）
    order.qty = orderCopy.qty;
    
    return trades;
}

} // namespace core
} // namespace matcher
