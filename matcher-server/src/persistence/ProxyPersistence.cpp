#include "persistence/ProxyPersistence.h"
#include "model/Order.h"
#include "model/Trade.h"
#include "ipc/IPCServer.h"
#include <nlohmann/json.hpp>
#include <iostream>

using json = nlohmann::json;

namespace matcher
{
    namespace persistence
    {

        ProxyPersistence::ProxyPersistence(ipc::IPCServer *ipcServer)
            : ipcServer_(ipcServer)
        {
            if (!ipcServer_)
            {
                throw std::invalid_argument("IPC server cannot be null");
            }
        }

        void ProxyPersistence::saveTrade(const model::Trade &trade)
        {
            try
            {
                json tradeJson;
                tradeJson["tradeId"] = trade.tradeId;
                tradeJson["clOrderIdBuy"] = trade.clOrderIdBuy;
                tradeJson["clOrderIdSell"] = trade.clOrderIdSell;
                tradeJson["securityId"] = trade.securityId;
                tradeJson["price"] = trade.price;
                tradeJson["quantity"] = trade.quantity;
                tradeJson["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(trade.timestamp.time_since_epoch()).count();

                json payload;
                payload["trade"] = tradeJson;

                bool success = sendCommand("SAVE_TRADE", payload.dump());
                if (!success)
                {
                    throw std::runtime_error("Failed to send SAVE_TRADE command");
                }

                std::cout << "[ProxyPersistence] Sent trade to Java layer: " << trade.tradeId << std::endl;
            }
            catch (const std::exception &e)
            {
                std::cerr << "[ProxyPersistence] Failed to save trade: " << e.what() << std::endl;
                throw;
            }
        }

        void ProxyPersistence::updateOrder(const model::Order &order)
        {
            try
            {
                json orderJson;
                orderJson["clOrderId"] = order.clOrderId;
                orderJson["securityId"] = order.securityId;
                orderJson["side"] = order.side;
                orderJson["price"] = order.price;
                orderJson["quantity"] = order.qty;
                orderJson["executedQuantity"] = order.executedQuantity;
                orderJson["lastExecutedPrice"] = order.lastExecutedPrice;
                orderJson["status"] = order.status;
                orderJson["orderType"] = order.orderType;
                orderJson["timeInForce"] = order.timeInForce;
                orderJson["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(order.timestamp.time_since_epoch()).count();

                json payload;
                payload["order"] = orderJson;

                bool success = sendCommand("UPDATE_ORDER", payload.dump());
                if (!success)
                {
                    throw std::runtime_error("Failed to send UPDATE_ORDER command");
                }

                std::cout << "[ProxyPersistence] Sent order update to Java layer: " << order.clOrderId << std::endl;
            }
            catch (const std::exception &e)
            {
                std::cerr << "[ProxyPersistence] Failed to update order: " << e.what() << std::endl;
                throw;
            }
        }

        std::vector<model::Order> ProxyPersistence::loadUnfinishedOrders(const std::string &securityId)
        {
            // 在生产环境中，Java层负责数据库查询
            // 这里返回空列表，实际实现中可能需要通过IPC请求数据

            std::cout << "[ProxyPersistence] Requesting unfinished orders for security: " << securityId << std::endl;

            // TODO: 实现通过IPC从Java层加载未完成订单
            // 目前返回空列表，表示没有未完成订单
            return {};
        }

        void ProxyPersistence::saveTrades(const std::vector<model::Trade> &trades)
        {
            if (trades.empty())
            {
                return;
            }

            try
            {
                json tradesArray = json::array();
                for (const auto &trade : trades)
                {
                    json tradeJson;
                    tradeJson["tradeId"] = trade.tradeId;
                    tradeJson["clOrderIdBuy"] = trade.clOrderIdBuy;
                    tradeJson["clOrderIdSell"] = trade.clOrderIdSell;
                    tradeJson["securityId"] = trade.securityId;
                    tradeJson["price"] = trade.price;
                    tradeJson["quantity"] = trade.quantity;
                    tradeJson["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(trade.timestamp.time_since_epoch()).count();
                    tradesArray.push_back(tradeJson);
                }

                json payload;
                payload["trades"] = tradesArray;

                bool success = sendCommand("SAVE_TRADES_BATCH", payload.dump());
                if (!success)
                {
                    throw std::runtime_error("Failed to send SAVE_TRADES_BATCH command");
                }

                std::cout << "[ProxyPersistence] Sent " << trades.size() << " trades to Java layer in batch" << std::endl;
            }
            catch (const std::exception &e)
            {
                std::cerr << "[ProxyPersistence] Failed to save trades batch: " << e.what() << std::endl;
                throw;
            }
        }

        void ProxyPersistence::updateOrders(const std::vector<model::Order> &orders)
        {
            if (orders.empty())
            {
                return;
            }

            try
            {
                json ordersArray = json::array();
                for (const auto &order : orders)
                {
                    json orderJson;
                    orderJson["clOrderId"] = order.clOrderId;
                    orderJson["securityId"] = order.securityId;
                    orderJson["side"] = order.side;
                    orderJson["price"] = order.price;
                    orderJson["quantity"] = order.qty;
                    orderJson["executedQuantity"] = order.executedQuantity;
                    orderJson["lastExecutedPrice"] = order.lastExecutedPrice;
                    orderJson["status"] = order.status;
                    orderJson["orderType"] = order.orderType;
                    orderJson["timeInForce"] = order.timeInForce;
                    orderJson["timestamp"] = std::chrono::duration_cast<std::chrono::milliseconds>(order.timestamp.time_since_epoch()).count();
                    ordersArray.push_back(orderJson);
                }

                json payload;
                payload["orders"] = ordersArray;

                bool success = sendCommand("UPDATE_ORDERS_BATCH", payload.dump());
                if (!success)
                {
                    throw std::runtime_error("Failed to send UPDATE_ORDERS_BATCH command");
                }

                std::cout << "[ProxyPersistence] Sent " << orders.size() << " orders to Java layer in batch" << std::endl;
            }
            catch (const std::exception &e)
            {
                std::cerr << "[ProxyPersistence] Failed to update orders batch: " << e.what() << std::endl;
                throw;
            }
        }

        bool ProxyPersistence::sendCommand(const std::string &type, const std::string &data)
        {
            if (!ipcServer_)
            {
                std::cerr << "[ProxyPersistence] IPC server not available" << std::endl;
                return false;
            }

            return ipcServer_->sendCommand(type, data);
        }

    } // namespace persistence
} // namespace matcher