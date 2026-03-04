#include "core/MatchingEngine.h"
#include "util/Logger.h"
#include <algorithm>
#include <iostream>
#include <chrono>
#include <queue>
#include <map>

namespace matcher
{
    namespace core
    {

        MatchingEngine::MatchingEngine(std::unique_ptr<persistence::IPersistence> persistence, ipc::IPCServer *ipcServer)
            : persistence_(std::move(persistence)), ipcServer_(ipcServer) {}

        MatchingEngine::~MatchingEngine() = default;

        OrderBook *MatchingEngine::getOrCreateBook(const std::string &securityId)
        {
            auto it = books_.find(securityId);
            if (it != books_.end())
                return it->second.get();
            auto pb = std::make_unique<OrderBook>();
            OrderBook *ptr = pb.get();
            books_.emplace(securityId, std::move(pb));
            return ptr;
        }

        void MatchingEngine::submitOrder(const matcher::model::Order &order)
        {
            std::lock_guard<std::recursive_mutex> lock(engineMutex_);

            matcher::util::Logger::logOrder(order.clOrderId,
                                            "提交订单: 证券=" + order.securityId +
                                                ", 方向=" + order.side +
                                                ", 价格=" + std::to_string(order.price) +
                                                ", 数量=" + std::to_string(order.qty));

            // 参数校验
            if (order.qty <= 0)
            {
                matcher::util::Logger::logOrder(order.clOrderId, "订单数量非法，拒绝订单");
                matcher::model::Order rejected = order;
                rejected.status = "REJECTED";
                if (persistence_)
                {
                    persistence_->updateOrder(rejected);
                }
                return;
            }

            // 标记为撮合中
            matcher::model::Order incoming = order;
            incoming.status = "MATCHING";

            // 执行撮合
            auto trades = matchOrder(incoming);

            // 记录撮合结果
            if (!trades.empty())
            {
                matcher::util::Logger::logOrder(order.clOrderId,
                                                "订单撮合成功: 生成" + std::to_string(trades.size()) + "笔成交");
            }
            else
            {
                matcher::util::Logger::logOrder(order.clOrderId,
                                                "订单进入订单簿等待撮合");
            }

            // 为每笔成交持久化并通知
            for (const auto &t : trades)
            {
                if (persistence_)
                {
                    matcher::util::Logger::logOrder(t.clOrderIdBuy,
                                                    "保存成交到数据库: " + t.tradeId +
                                                        ", 价格=" + std::to_string(t.price) +
                                                        ", 数量=" + std::to_string(t.quantity));
                    persistence_->saveTrade(t);
                }
                if (ipcServer_)
                {
                    matcher::util::Logger::logOrder(t.clOrderIdBuy,
                                                    "发送成交报告: " + t.tradeId);
                    ipcServer_->sendExecutionReport(t);
                }
            }

            // 计算该订单在订单簿中的剩余量（若存在）
            int remainingQty = 0;
            auto snapshot = getOrderBook(order.securityId);
            for (const auto &o : snapshot)
            {
                if (o.clOrderId == order.clOrderId)
                {
                    remainingQty = o.qty;
                    break;
                }
            }

            // 更新订单状态并持久化（best-effort）
            matcher::model::Order updated = order;
            updated.qty = remainingQty;
            if (!trades.empty())
            {
                if (remainingQty <= 0)
                    updated.status = "FULL_FILLED";
                else
                    updated.status = "PART_FILLED";
            }
            else
            {
                // 未撮合到成交，处于挂单状态（MATCHING）
                updated.status = "MATCHING";
            }

            if (persistence_)
            {
                matcher::util::Logger::logOrder(order.clOrderId, "更新订单状态到持久层: " + updated.status);
                persistence_->updateOrder(updated);
            }

            matcher::util::Logger::logOrder(order.clOrderId, "订单处理完成");
        }

        bool MatchingEngine::cancelOrder(const std::string &clOrderId)
        {
            std::lock_guard<std::recursive_mutex> lock(engineMutex_);

            matcher::util::Logger::logOrder(clOrderId, "尝试取消订单");

            for (auto &kv : books_)
            {
                if (kv.second->cancel(clOrderId))
                {
                    matcher::util::Logger::logOrder(clOrderId, "订单取消成功");
                    // Could call persistence_->updateOrder if needed
                    return true;
                }
            }

            matcher::util::Logger::logOrder(clOrderId, "订单取消失败，未找到订单");
            return false;
        }

        std::vector<matcher::model::Trade> MatchingEngine::matchBatch(
            const std::string &market,
            const std::string &securityId,
            const std::vector<matcher::model::Order> &buyOrders,
            const std::vector<matcher::model::Order> &sellOrders)
        {

            std::lock_guard<std::recursive_mutex> lock(engineMutex_);

            matcher::util::Logger::logSys("MatchingEngine::matchBatch",
                                          "开始批量撮合: 市场=" + market +
                                              ", 证券=" + securityId +
                                              ", 买单数量=" + std::to_string(buyOrders.size()) +
                                              ", 卖单数量=" + std::to_string(sellOrders.size()));

            // Simple implementation: create a fresh temporary orderbook and feed orders
            OrderBook tmp;
            std::vector<matcher::model::Trade> trades;

            // insert all buy orders into tmp (as existing book)
            for (const auto &o : buyOrders)
            {
                auto t = tmp.submit(o);
                trades.insert(trades.end(), t.begin(), t.end());
            }
            for (const auto &o : sellOrders)
            {
                auto t = tmp.submit(o);
                trades.insert(trades.end(), t.begin(), t.end());
            }

            matcher::util::Logger::logSys("MatchingEngine::matchBatch",
                                          "批量撮合完成: 生成" + std::to_string(trades.size()) + "笔成交");

            // persist + notify
            for (const auto &tr : trades)
            {
                if (persistence_)
                {
                    matcher::util::Logger::logOrder(tr.clOrderIdBuy,
                                                    "批量撮合保存成交: " + tr.tradeId);
                    persistence_->saveTrade(tr);
                }
                if (ipcServer_)
                {
                    matcher::util::Logger::logOrder(tr.clOrderIdBuy,
                                                    "批量撮合发送成交报告: " + tr.tradeId);
                    ipcServer_->sendExecutionReport(tr);
                }
            }

            matcher::util::Logger::logSys("MatchingEngine::matchBatch",
                                          "批量撮合处理完成");
            return trades;
        }

        std::vector<matcher::model::Order> MatchingEngine::getOrderBook(const std::string &securityId) const
        {
            std::lock_guard<std::recursive_mutex> lock(engineMutex_);
            auto it = books_.find(securityId);
            if (it == books_.end())
                return {};
            return it->second->snapshot();
        }

        //  撮合逻辑实现
        std::vector<matcher::model::Trade> MatchingEngine::matchOrder(matcher::model::Order& order) {
            std::vector<matcher::model::Trade> trades;
            
            if (order.qty <= 0) {
                matcher::util::Logger::logOrder(order.clOrderId, "订单数量非法，拒绝撮合");
                return trades;
            }

            std::string securityId = order.securityId;
            std::string newOrderSide = order.side;
            int remainingQty = order.qty;
            order.status = "MATCHING";

            try {
                // 获取对应的订单簿
                OrderBook* book = getOrCreateBook(securityId);
                
                if (newOrderSide == "B") {
                    // 买单：匹配卖单（价格升序）
                    auto& sellPriceMap = book->getSellPriceMap(); // 升序map
                    
                    // 遍历对手方最优价格（卖单价格升序，从最低价开始）
                    for (auto it = sellPriceMap.begin(); it != sellPriceMap.end() && remainingQty > 0; ) {
                        double sellPrice = it->first;
                        
                        // 终止条件：剩余数量为0 或 当前价格不满足撮合条件（买价 < 卖价）
                        if (remainingQty <= 0 || !isPriceMatch(newOrderSide, order.price, sellPrice)) {
                            break;
                        }

                        // 获取当前价格下的对手方订单队列
                        auto& counterOrderQueue = it->second;
                        if (counterOrderQueue.empty()) {
                            ++it;
                            continue;
                        }

                        // 逐笔匹配队列中的订单（时间优先）
                        for (auto qit = counterOrderQueue.begin(); qit != counterOrderQueue.end() && remainingQty > 0; ) {
                            matcher::model::Order& counterOrder = *qit;
                            if (counterOrder.qty <= 0) {
                                ++qit;
                                continue;
                            }

                            // 计算成交数量
                            int matchQty = std::min(remainingQty, counterOrder.qty);
                            // 生成成交价
                            double matchPrice = generatePrice(order, counterOrder);

                            // 执行成交逻辑
                            executeMatch(order, counterOrder, matchQty, matchPrice);

                            // 创建成交记录
                            auto now = std::chrono::system_clock::now();
                            matcher::model::Trade trade;
                            trade.tradeId = "T" + std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count());
                            trade.securityId = securityId;
                            trade.price = matchPrice;
                            trade.quantity = matchQty;
                            trade.timestamp = now;
                            trade.clOrderIdBuy = order.clOrderId;
                            trade.clOrderIdSell = counterOrder.clOrderId;
                            
                            trades.push_back(trade);

                            // 更新剩余数量
                            remainingQty -= matchQty;

                            // 若对手方订单完全成交，从队列移除
                            if (counterOrder.qty == 0) {
                                qit = counterOrderQueue.erase(qit);
                                matcher::util::Logger::logOrder(order.clOrderId, 
                                    "对手方订单[" + counterOrder.clOrderId + "]完全成交，已从队列移除");
                            } else {
                                ++qit;
                            }

                            // 若新订单剩余量为0，终止撮合
                            if (remainingQty <= 0) {
                                break;
                            }
                        }

                        // 若当前价格队列空，移除该价格节点
                        if (counterOrderQueue.empty()) {
                            it = sellPriceMap.erase(it);
                            matcher::util::Logger::logOrder(order.clOrderId,
                                "股票[" + securityId + "]卖单价格[" + std::to_string(sellPrice) + "]队列已空，移除该价格节点");
                        } else {
                            ++it;
                        }
                    }
                    
                } else if (newOrderSide == "S") {
                    // 卖单：匹配买单（价格降序，从最高价开始）
                    auto& buyPriceMap = book->getBuyPriceMap(); // 降序map
                    
                    // 遍历对手方最优价格（买单价格降序，从最高价开始）
                    for (auto it = buyPriceMap.begin(); it != buyPriceMap.end() && remainingQty > 0; ) {
                        double buyPrice = it->first;
                        
                        // 终止条件：剩余数量为0 或 当前价格不满足撮合条件（卖价 > 买价）
                        if (remainingQty <= 0 || !isPriceMatch(newOrderSide, order.price, buyPrice)) {
                            break;
                        }

                        // 获取当前价格下的对手方订单队列
                        auto& counterOrderQueue = it->second;
                        if (counterOrderQueue.empty()) {
                            ++it;
                            continue;
                        }

                        // 逐笔匹配队列中的订单（时间优先）
                        for (auto qit = counterOrderQueue.begin(); qit != counterOrderQueue.end() && remainingQty > 0; ) {
                            matcher::model::Order& counterOrder = *qit;
                            if (counterOrder.qty <= 0) {
                                ++qit;
                                continue;
                            }

                            // 计算成交数量
                            int matchQty = std::min(remainingQty, counterOrder.qty);
                            // 生成成交价
                            double matchPrice = generatePrice(order, counterOrder);

                            // 执行成交逻辑
                            executeMatch(order, counterOrder, matchQty, matchPrice);

                            // 创建成交记录
                            auto now = std::chrono::system_clock::now();
                            matcher::model::Trade trade;
                            trade.tradeId = "T" + std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count());
                            trade.securityId = securityId;
                            trade.price = matchPrice;
                            trade.quantity = matchQty;
                            trade.timestamp = now;
                            trade.clOrderIdBuy = counterOrder.clOrderId;
                            trade.clOrderIdSell = order.clOrderId;
                            
                            trades.push_back(trade);

                            // 更新剩余数量
                            remainingQty -= matchQty;

                            // 若对手方订单完全成交，从队列移除
                            if (counterOrder.qty == 0) {
                                qit = counterOrderQueue.erase(qit);
                                matcher::util::Logger::logOrder(order.clOrderId, 
                                    "对手方订单[" + counterOrder.clOrderId + "]完全成交，已从队列移除");
                            } else {
                                ++qit;
                            }

                            // 若新订单剩余量为0，终止撮合
                            if (remainingQty <= 0) {
                                break;
                            }
                        }

                        // 若当前价格队列空，移除该价格节点
                        if (counterOrderQueue.empty()) {
                            it = buyPriceMap.erase(it);
                            matcher::util::Logger::logOrder(order.clOrderId,
                                "股票[" + securityId + "]买单价格[" + std::to_string(buyPrice) + "]队列已空，移除该价格节点");
                        } else {
                            ++it;
                        }
                    }
                } else {
                    matcher::util::Logger::logOrder(order.clOrderId, "订单方向非法: " + newOrderSide);
                    order.status = "REJECTED";
                    return trades;
                }

                // 更新新订单状态
                updateNewOrderStatus(order, remainingQty);

                // 若新订单未完全成交，添加到订单簿挂单
                if (remainingQty > 0) {
                    order.qty = remainingQty;
                    book->addOrder(order);
                    matcher::util::Logger::logOrder(order.clOrderId, 
                        "新订单[" + order.clOrderId + "]部分成交，剩余数量[" + std::to_string(remainingQty) + "]已挂单");
                } else {
                    matcher::util::Logger::logOrder(order.clOrderId, 
                        "新订单[" + order.clOrderId + "]完全成交，无需挂单");
                }

            } catch (const std::exception& e) {
                matcher::util::Logger::logOrder(order.clOrderId, 
                    "撮合订单[" + order.clOrderId + "]时发生异常: " + std::string(e.what()));
                order.status = "REJECTED";
            }

            return trades;
        }

        // 辅助方法实现
        bool MatchingEngine::isPriceMatch(const std::string& side, double newOrderPrice, double counterPrice) {
            // 买订单：买价 >= 卖价
            // 卖订单：卖价 <= 买价
            if (side == "B") {
                return newOrderPrice >= counterPrice;
            } else if (side == "S") {
                return newOrderPrice <= counterPrice;
            }
            return false;
        }

        void MatchingEngine::executeMatch(matcher::model::Order& newOrder, matcher::model::Order& counterOrder, int matchQty, double matchPrice) {
            // 更新新订单数量（这里主要是更新remainingQty，实际在matchOrder中处理）
            // 更新对手方订单数量
            counterOrder.qty -= matchQty;

            // 日志记录成交信息
            matcher::util::Logger::logOrder(newOrder.clOrderId,
                "撮合成交：新订单[" + newOrder.clOrderId + "] vs 对手方订单[" + counterOrder.clOrderId + 
                "] | 成交价格[" + std::to_string(matchPrice) + "] | 成交数量[" + std::to_string(matchQty) + 
                "] | 新订单剩余[" + std::to_string(newOrder.qty - matchQty) + "] | 对手方剩余[" + std::to_string(counterOrder.qty) + "]");
        }

        void MatchingEngine::updateNewOrderStatus(matcher::model::Order& order, int remainingQty) {
            if (remainingQty <= 0) {
                order.status = "FULL_FILLED"; // 完全成交
            } else if (remainingQty < order.qty) {
                order.status = "PART_FILLED"; // 部分成交
            } else {
                order.status = "MATCHING"; // 未成交，挂单中
            }
        }

        double MatchingEngine::generatePrice(const matcher::model::Order& newOrder, const matcher::model::Order& counterOrder) {
            // 简单实现：使用对手方订单的价格
            // 实际中可能使用更复杂的逻辑，如取中间价、最新成交价等
            return counterOrder.price;
        }

        // 工厂函数实现
        std::unique_ptr<IMatchingEngine> createMatchingEngine(
            std::unique_ptr<persistence::IPersistence> persistence,
            ipc::IPCServer *ipcServer)
        {
            return std::make_unique<MatchingEngine>(std::move(persistence), ipcServer);
        }

    } // namespace core
} // namespace matcher
