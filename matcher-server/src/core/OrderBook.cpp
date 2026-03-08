#include "core/OrderBook.h"
#include "util/Logger.h"
#include <algorithm>
#include <chrono>
#include <sstream>

namespace matcher
{
    namespace core
    {

        using namespace matcher::model;

        std::vector<Trade> OrderBook::submit(const Order &order)
        {
            std::lock_guard<std::mutex> lock(mutex_);
            // Copy order to modify local qty
            Order o = order;
            return matchAgainst(std::move(o));
        }

        bool OrderBook::cancel(const std::string &clOrderId)
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto remove_from_map = [&](auto &m) -> bool
            {
                for (auto it = m.begin(); it != m.end(); ++it)
                {
                    auto &vec = it->second;
                    auto vit = std::find_if(vec.begin(), vec.end(), [&](const Order &o)
                                            { return o.clOrderId == clOrderId; });
                    if (vit != vec.end())
                    {
                        vec.erase(vit);
                        if (vec.empty())
                            m.erase(it);
                        return true;
                    }
                }
                return false;
            };

            if (remove_from_map(buyOrders_))
                return true;
            if (remove_from_map(sellOrders_))
                return true;
            return false;
        }

        std::vector<Order> OrderBook::snapshot() const
        {
            std::lock_guard<std::mutex> lock(mutex_);
            std::vector<Order> res;
            for (const auto &[price, vec] : buyOrders_)
            {
                res.insert(res.end(), vec.begin(), vec.end());
            }
            for (const auto &[price, vec] : sellOrders_)
            {
                res.insert(res.end(), vec.begin(), vec.end());
            }
            return res;
        }

        std::vector<Trade> OrderBook::matchAgainst(Order order)
        {
            std::vector<Trade> trades;

            bool isBuy = (order.side == "B");

            matcher::util::Logger::logOrder(order.clOrderId,
                                            "开始撮合算法: 方向=" + std::string(isBuy ? "买" : "卖") +
                                                ", 价格=" + std::to_string(order.price) +
                                                ", 数量=" + std::to_string(order.qty));

            // Simple limit-order matching: price-time priority
            if (isBuy)
            {
                matcher::util::Logger::logOrder(order.clOrderId,
                                                "买单撮合: 寻找卖单队列，当前卖单价格档位数量=" + std::to_string(sellOrders_.size()));

                // Match against lowest-price sell orders
                for (auto sit = sellOrders_.begin(); sit != sellOrders_.end() && order.qty > 0;)
                {
                    double sellPrice = sit->first;
                    if (order.price < sellPrice)
                    {
                        matcher::util::Logger::logOrder(order.clOrderId,
                                                        "价格不匹配: 买单价格" + std::to_string(order.price) +
                                                            " < 卖单价格" + std::to_string(sellPrice) + "，停止撮合");
                        break; // no match
                    }

                    auto &vec = sit->second;
                    matcher::util::Logger::logOrder(order.clOrderId,
                                                    "匹配价格档位: " + std::to_string(sellPrice) +
                                                        ", 该档位订单数量=" + std::to_string(vec.size()));

                    for (auto vit = vec.begin(); vit != vec.end() && order.qty > 0;)
                    {
                        Order &sellOrder = *vit;
                        int64_t execQty = std::min<int64_t>(order.qty, sellOrder.qty);
                        if (execQty <= 0)
                        {
                            ++vit;
                            continue;
                        }

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

                        matcher::util::Logger::logOrder(order.clOrderId,
                                                        "生成成交: " + t.tradeId +
                                                            ", 价格=" + std::to_string(t.price) +
                                                            ", 数量=" + std::to_string(t.quantity) +
                                                            ", 对手方订单=" + sellOrder.clOrderId);

                        // update quantities
                        order.qty -= static_cast<int>(execQty);
                        sellOrder.qty -= static_cast<int>(execQty);

                        if (sellOrder.qty == 0)
                        {
                            matcher::util::Logger::logOrder(sellOrder.clOrderId,
                                                            "卖单完全成交，从订单簿移除");
                            vit = vec.erase(vit);
                        }
                        else
                        {
                            matcher::util::Logger::logOrder(sellOrder.clOrderId,
                                                            "卖单部分成交，剩余数量=" + std::to_string(sellOrder.qty));
                            ++vit;
                        }
                    }

                    if (vec.empty())
                    {
                        matcher::util::Logger::logOrder(order.clOrderId,
                                                        "价格档位" + std::to_string(sellPrice) + "已清空，移除该档位");
                        sit = sellOrders_.erase(sit);
                    }
                    else
                    {
                        ++sit;
                    }
                }

                // If remaining, add to buy book
                if (order.qty > 0)
                {
                    matcher::util::Logger::logOrder(order.clOrderId,
                                                    "买单未完全成交，剩余数量=" + std::to_string(order.qty) +
                                                        "，加入买单簿价格档位" + std::to_string(order.price));
                    buyOrders_[order.price].push_back(order);
                }
                else
                {
                    matcher::util::Logger::logOrder(order.clOrderId, "买单完全成交");
                }
            }
            else
            {
                matcher::util::Logger::logOrder(order.clOrderId,
                                                "卖单撮合: 寻找买单队列，当前买单价格档位数量=" + std::to_string(buyOrders_.size()));

                // sell order: match against highest-price buy orders
                for (auto bit = buyOrders_.begin(); bit != buyOrders_.end() && order.qty > 0;)
                {
                    double buyPrice = bit->first;
                    if (order.price > buyPrice)
                    {
                        matcher::util::Logger::logOrder(order.clOrderId,
                                                        "价格不匹配: 卖单价格" + std::to_string(order.price) +
                                                            " > 买单价格" + std::to_string(buyPrice) + "，停止撮合");
                        break; // no match for sell
                    }

                    auto &vec = bit->second;
                    matcher::util::Logger::logOrder(order.clOrderId,
                                                    "匹配价格档位: " + std::to_string(buyPrice) +
                                                        ", 该档位订单数量=" + std::to_string(vec.size()));

                    for (auto vit = vec.begin(); vit != vec.end() && order.qty > 0;)
                    {
                        Order &buyOrder = *vit;
                        int64_t execQty = std::min<int64_t>(order.qty, buyOrder.qty);
                        if (execQty <= 0)
                        {
                            ++vit;
                            continue;
                        }

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

                        matcher::util::Logger::logOrder(order.clOrderId,
                                                        "生成成交: " + t.tradeId +
                                                            ", 价格=" + std::to_string(t.price) +
                                                            ", 数量=" + std::to_string(t.quantity) +
                                                            ", 对手方订单=" + buyOrder.clOrderId);

                        order.qty -= static_cast<int>(execQty);
                        buyOrder.qty -= static_cast<int>(execQty);

                        if (buyOrder.qty == 0)
                        {
                            matcher::util::Logger::logOrder(buyOrder.clOrderId,
                                                            "买单完全成交，从订单簿移除");
                            vit = vec.erase(vit);
                        }
                        else
                        {
                            matcher::util::Logger::logOrder(buyOrder.clOrderId,
                                                            "买单部分成交，剩余数量=" + std::to_string(buyOrder.qty));
                            ++vit;
                        }
                    }

                    if (vec.empty())
                    {
                        matcher::util::Logger::logOrder(order.clOrderId,
                                                        "价格档位" + std::to_string(buyPrice) + "已清空，移除该档位");
                        bit = buyOrders_.erase(bit);
                    }
                    else
                    {
                        ++bit;
                    }
                }

                if (order.qty > 0)
                {
                    matcher::util::Logger::logOrder(order.clOrderId,
                                                    "卖单未完全成交，剩余数量=" + std::to_string(order.qty) +
                                                        "，加入卖单簿价格档位" + std::to_string(order.price));
                    sellOrders_[order.price].push_back(order);
                }
                else
                {
                    matcher::util::Logger::logOrder(order.clOrderId, "卖单完全成交");
                }
            }

            matcher::util::Logger::logOrder(order.clOrderId,
                                            "撮合算法完成: 生成" + std::to_string(trades.size()) + "笔成交");
            return trades;
        }

    } // namespace core
} // namespace matcher
