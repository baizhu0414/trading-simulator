#include "core/MatchingEngine.h"
#include "util/Logger.h"
#include <algorithm>
#include <iostream>
#include <chrono>

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
            std::lock_guard<std::mutex> lock(engineMutex_);

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

            // persist and notify for each trade
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

            // update orders if persistence supports it (best-effort)
            // note: OrderBook currently updates quantities in-place in its structures

            matcher::util::Logger::logOrder(order.clOrderId, "订单处理完成");
        }

        bool MatchingEngine::cancelOrder(const std::string &clOrderId)
        {
            std::lock_guard<std::mutex> lock(engineMutex_);

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

            std::lock_guard<std::mutex> lock(engineMutex_);

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
            std::lock_guard<std::mutex> lock(engineMutex_);
            auto it = books_.find(securityId);
            if (it == books_.end())
                return {};
            return it->second->snapshot();
        }

        //  撮合逻辑实现
        std::vector<matcher::model::Trade> MatchingEngine::matchOrder(matcher::model::Order& order) {
            std::lock_guard<std::recursive_mutex> lock(engineMutex_);
            
            if (order.qty <= 0) {
                matcher::util::Logger::logOrder(order.clOrderId, "订单数量非法，拒绝撮合");
                return {};
            }
            
            try {
                OrderBook* book = getOrCreateBook(order.securityId);
                return book->matchOrder(order);
            } catch (const std::exception& e) {
                matcher::util::Logger::logOrder(order.clOrderId, 
                    "撮合订单[" + order.clOrderId + "]时发生异常: " + std::string(e.what()));
                order.status = "REJECTED";
                return {};
            }
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
