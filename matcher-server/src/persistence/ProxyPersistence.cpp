#include "persistence/ProxyPersistence.h"
#include "model/Order.h"
#include "model/Trade.h"
#include "ipc/IPCServer.h"
#include "util/Logger.h"
#include <nlohmann/json.hpp>
#include <chrono>
#include <iostream>

using json = nlohmann::json;

namespace matcher
{
    namespace persistence
    {

        ProxyPersistence::ProxyPersistence(ipc::IPCServer *ipcServer)
            : ipcServer_(ipcServer)
        {
            matcher::util::Logger::logDB("CONSTRUCTOR", "INFO", "ProxyPersistence构造函数调用");

            if (!ipcServer_)
            {
                matcher::util::Logger::logDB("CONSTRUCTOR", "ERROR", "IPC服务器指针为空");
                throw std::invalid_argument("IPC server cannot be null");
            }

            matcher::util::Logger::logDB("CONSTRUCTOR", "SUCCESS", "ProxyPersistence初始化成功");
        }

        void ProxyPersistence::saveTrade(const model::Trade &trade)
        {
            try
            {
                matcher::util::Logger::logDB("SAVE_TRADE", "START", "开始通过IPC保存成交: " + trade.tradeId);

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

                matcher::util::Logger::logDB("SAVE_TRADE", "EXECUTING", "发送成交数据到Java层: " + trade.tradeId);

                bool success = sendCommand("SAVE_TRADE", payload.dump());
                if (!success)
                {
                    matcher::util::Logger::logDB("SAVE_TRADE", "FAILURE", "IPC发送失败: " + trade.tradeId);
                    throw std::runtime_error("Failed to send SAVE_TRADE command");
                }

                matcher::util::Logger::logDB("SAVE_TRADE", "SUCCESS", "成交已发送到Java层: " + trade.tradeId + ", 价格: " + std::to_string(trade.price) + ", 数量: " + std::to_string(trade.quantity));
                std::cout << "[ProxyPersistence] Sent trade to Java layer: " << trade.tradeId << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("SAVE_TRADE", "FAILURE", "保存成交失败: " + trade.tradeId + ", 错误: " + std::string(e.what()));
                std::cerr << "[ProxyPersistence] Failed to save trade: " << e.what() << std::endl;
                throw;
            }
        }

        void ProxyPersistence::updateOrder(const model::Order &order)
        {
            try
            {
                matcher::util::Logger::logDB("UPDATE_ORDER", "START", "开始通过IPC更新订单: " + order.clOrderId + ", 状态: " + order.status + ", 数量: " + std::to_string(order.qty));

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

                matcher::util::Logger::logDB("UPDATE_ORDER", "EXECUTING", "发送订单更新到Java层: " + order.clOrderId);

                bool success = sendCommand("UPDATE_ORDER", payload.dump());
                if (!success)
                {
                    matcher::util::Logger::logDB("UPDATE_ORDER", "FAILURE", "IPC发送失败: " + order.clOrderId);
                    throw std::runtime_error("Failed to send UPDATE_ORDER command");
                }

                matcher::util::Logger::logDB("UPDATE_ORDER", "SUCCESS", "订单更新已发送到Java层: " + order.clOrderId + ", 新状态: " + order.status);
                std::cout << "[ProxyPersistence] Sent order update to Java layer: " << order.clOrderId << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("UPDATE_ORDER", "FAILURE", "更新订单失败: " + order.clOrderId + ", 错误: " + std::string(e.what()));
                std::cerr << "[ProxyPersistence] Failed to update order: " << e.what() << std::endl;
                throw;
            }
        }

        std::vector<model::Order> ProxyPersistence::loadUnfinishedOrders(const std::string &securityId)
        {
            matcher::util::Logger::logDB("LOAD_ORDERS", "START", "请求加载未完成订单: " + securityId);
            matcher::util::Logger::logDB("LOAD_ORDERS", "WARNING", "当前返回空列表,功能待实现 (TODO)");

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
                matcher::util::Logger::logDB("SAVE_TRADES_BATCH", "START", "开始批量保存成交: " + std::to_string(trades.size()) + " 条");

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

                matcher::util::Logger::logDB("SAVE_TRADES_BATCH", "EXECUTING", "发送批量成交数据到Java层: " + std::to_string(trades.size()) + " 条");

                bool success = sendCommand("SAVE_TRADES_BATCH", payload.dump());
                if (!success)
                {
                    matcher::util::Logger::logDB("SAVE_TRADES_BATCH", "FAILURE", "IPC批量发送失败");
                    throw std::runtime_error("Failed to send SAVE_TRADES_BATCH command");
                }

                matcher::util::Logger::logDB("SAVE_TRADES_BATCH", "SUCCESS", "批量成交已发送到Java层: " + std::to_string(trades.size()) + " 条");
                std::cout << "[ProxyPersistence] Sent " << trades.size() << " trades to Java layer in batch" << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("SAVE_TRADES_BATCH", "FAILURE", "批量保存成交失败: " + std::string(e.what()));
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
                matcher::util::Logger::logDB("UPDATE_ORDERS_BATCH", "START", "开始批量更新订单: " + std::to_string(orders.size()) + " 条");

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

                matcher::util::Logger::logDB("UPDATE_ORDERS_BATCH", "EXECUTING", "发送批量订单更新到Java层: " + std::to_string(orders.size()) + " 条");

                bool success = sendCommand("UPDATE_ORDERS_BATCH", payload.dump());
                if (!success)
                {
                    matcher::util::Logger::logDB("UPDATE_ORDERS_BATCH", "FAILURE", "IPC批量发送失败");
                    throw std::runtime_error("Failed to send UPDATE_ORDERS_BATCH command");
                }

                matcher::util::Logger::logDB("UPDATE_ORDERS_BATCH", "SUCCESS", "批量订单更新已发送到Java层: " + std::to_string(orders.size()) + " 条");
                std::cout << "[ProxyPersistence] Sent " << orders.size() << " orders to Java layer in batch" << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("UPDATE_ORDERS_BATCH", "FAILURE", "批量更新订单失败: " + std::string(e.what()));
                std::cerr << "[ProxyPersistence] Failed to update orders batch: " << e.what() << std::endl;
                throw;
            }
        }

        bool ProxyPersistence::sendCommand(const std::string &type, const std::string &data)
        {
            if (!ipcServer_)
            {
                matcher::util::Logger::logDB("IPC_SEND", "ERROR", "IPC服务器不可用, 命令类型: " + type);
                std::cerr << "[ProxyPersistence] IPC server not available" << std::endl;
                return false;
            }

            matcher::util::Logger::logDB("IPC_SEND", "INFO", "发送IPC命令: " + type + ", 数据长度: " + std::to_string(data.length()));

            bool result = ipcServer_->sendCommand(type, data);

            if (result)
            {
                matcher::util::Logger::logDB("IPC_SEND", "SUCCESS", "IPC命令发送成功: " + type);
            }
            else
            {
                matcher::util::Logger::logDB("IPC_SEND", "FAILURE", "IPC命令发送失败: " + type);
            }

            return result;
        }

    } // namespace persistence
} // namespace matcher