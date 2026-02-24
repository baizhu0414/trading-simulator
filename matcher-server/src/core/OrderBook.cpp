#include "core/OrderBook.h"
#include <algorithm>
#include <chrono>
#include <sstream>

namespace matcher {
namespace core {

using namespace matcher::model;

std::vector<Trade> OrderBook::submit(const Order& order) {
    std::lock_guard<std::mutex> lock(mutex_);
    // Copy order to modify local qty
    Order o = order;
    return matchAgainst(std::move(o));
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

    // Simple limit-order matching: price-time priority
    if (isBuy) {
        // Match against lowest-price sell orders
        for (auto sit = sellOrders_.begin(); sit != sellOrders_.end() && order.qty > 0; ) {
            double sellPrice = sit->first;
            if (order.price < sellPrice) break; // no match

            auto& vec = sit->second;
            for (auto vit = vec.begin(); vit != vec.end() && order.qty > 0; ) {
                Order& sellOrder = *vit;
                int64_t execQty = std::min<int64_t>(order.qty, sellOrder.qty);
                if (execQty <= 0) { ++vit; continue; }

                // make trade
                auto now = std::chrono::system_clock::now();
                Trade t;
                t.tradeId = "T" + std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count());
                t.clOrderIdBuy = order.clOrderId;
                t.clOrderIdSell = sellOrder.clOrderId;
                t.securityId = order.securityId;
                t.price = sellOrder.price;
                t.quantity = execQty;
                t.timestamp = now;

                trades.push_back(t);

                // update quantities
                order.qty -= static_cast<int>(execQty);
                sellOrder.qty -= static_cast<int>(execQty);

                if (sellOrder.qty == 0) {
                    vit = vec.erase(vit);
                } else {
                    ++vit;
                }
            }

            if (vec.empty()) sit = sellOrders_.erase(sit); else ++sit;
        }

        // If remaining, add to buy book
        if (order.qty > 0) {
            buyOrders_[order.price].push_back(order);
        }
    } else {
        // sell order: match against highest-price buy orders
        for (auto bit = buyOrders_.begin(); bit != buyOrders_.end() && order.qty > 0; ) {
            double buyPrice = bit->first;
            if (order.price > buyPrice) break; // no match for sell

            auto& vec = bit->second;
            for (auto vit = vec.begin(); vit != vec.end() && order.qty > 0; ) {
                Order& buyOrder = *vit;
                int64_t execQty = std::min<int64_t>(order.qty, buyOrder.qty);
                if (execQty <= 0) { ++vit; continue; }

                auto now = std::chrono::system_clock::now();
                Trade t;
                t.tradeId = "T" + std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count());
                t.clOrderIdBuy = buyOrder.clOrderId;
                t.clOrderIdSell = order.clOrderId;
                t.securityId = order.securityId;
                t.price = buyOrder.price;
                t.quantity = execQty;
                t.timestamp = now;

                trades.push_back(t);

                order.qty -= static_cast<int>(execQty);
                buyOrder.qty -= static_cast<int>(execQty);

                if (buyOrder.qty == 0) {
                    vit = vec.erase(vit);
                } else {
                    ++vit;
                }
            }

            if (vec.empty()) bit = buyOrders_.erase(bit); else ++bit;
        }

        if (order.qty > 0) {
            sellOrders_[order.price].push_back(order);
        }
    }

    return trades;
}

} // namespace core
} // namespace matcher
