#include "ipc/IPCServer.h"
#include "model/Order.h"
#include "model/Trade.h"
#include <iostream>
#include <memory>
#include <string>
#include <functional>
#include <mutex>
#include <thread>
#include <atomic>
#include <queue>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

namespace matcher {
namespace ipc {

/**
 * @brief 基于标准输入/输出的IPC服务器实现
 * 
 * 该实现通过标准输入接收JSON格式的订单数据，
 * 通过标准输出发送成交回报和命令。
 */
class StdioIPCServer : public IPCServer {
private:
    std::function<void(model::Order)> orderCallback_;
    std::atomic<bool> running_{false};
    std::unique_ptr<std::thread> ioThread_;
    std::mutex outputMutex_;
    
public:
    StdioIPCServer() = default;
    
    virtual ~StdioIPCServer() {
        stop();
    }
    
    /**
     * @brief 启动IPC服务器
     */
    virtual bool start() override {
        if (running_) {
            return false;
        }
        
        running_ = true;
        
        // 启动I/O线程处理标准输入
        ioThread_ = std::make_unique<std::thread>([this]() {
            this->runIO();
        });
        
        return true;
    }
    
    /**
     * @brief 停止IPC服务器
     */
    virtual void stop() override {
        running_ = false;
        
        if (ioThread_ && ioThread_->joinable()) {
            ioThread_->join();
        }
    }
    
    /**
     * @brief 设置订单接收回调函数
     */
    virtual void setOrderCallback(std::function<void(model::Order)> callback) override {
        orderCallback_ = std::move(callback);
    }
    
    /**
     * @brief 发送成交回报
     */
    virtual bool sendExecutionReport(const model::Trade& trade) override {
        try {
            json j;
            j["type"] = "EXECUTION_REPORT";
            j["data"] = {
                {"tradeId", trade.tradeId},
                {"clOrderIdBuy", trade.clOrderIdBuy},
                {"clOrderIdSell", trade.clOrderIdSell},
                {"securityId", trade.securityId},
                {"price", trade.price},
                {"quantity", trade.quantity},
                {"timestamp", trade.timestamp}
            };
            
            std::lock_guard<std::mutex> lock(outputMutex_);
            std::cout << j.dump() << std::endl;
            return true;
        } catch (const std::exception& e) {
            // 日志记录错误
            return false;
        }
    }
    
    /**
     * @brief 发送通用命令给Java层
     */
    virtual bool sendCommand(const std::string& type, const std::string& payload) override {
        try {
            json j;
            j["type"] = type;
            j["payload"] = json::parse(payload);
            
            std::lock_guard<std::mutex> lock(outputMutex_);
            std::cout << j.dump() << std::endl;
            return true;
        } catch (const std::exception& e) {
            // 日志记录错误
            return false;
        }
    }
    
private:
    /**
     * @brief I/O线程主循环
     */
    void runIO() {
        std::string line;
        
        while (running_ && std::getline(std::cin, line)) {
            try {
                if (line.empty()) {
                    continue;
                }
                
                auto j = json::parse(line);
                std::string msgType = j["type"];
                
                if (msgType == "NEW_ORDER" && orderCallback_) {
                    auto data = j["data"];
                    model::Order order;
                    
                    // 解析订单字段
                    order.clOrderId = data["clOrderId"];
                    order.securityId = data["securityId"];
                    order.side = data["side"];
                    order.price = data["price"];
                    order.quantity = data["quantity"];
                    order.orderType = data["orderType"];
                    order.timeInForce = data["timeInForce"];
                    order.timestamp = data["timestamp"];
                    
                    // 调用订单回调
                    orderCallback_(std::move(order));
                } else if (msgType == "CANCEL_ORDER") {
                    // 处理撤单请求
                    // TODO: 实现撤单逻辑
                } else if (msgType == "HEARTBEAT") {
                    // 响应心跳
                    sendCommand("HEARTBEAT_RESPONSE", "{}");
                }
            } catch (const std::exception& e) {
                // 发送错误响应
                json errorResp;
                errorResp["type"] = "ERROR";
                errorResp["message"] = std::string("解析错误: ") + e.what();
                
                std::lock_guard<std::mutex> lock(outputMutex_);
                std::cout << errorResp.dump() << std::endl;
            }
        }
    }
};

/**
 * @brief IPC服务器工厂函数实现
 */
std::unique_ptr<IPCServer> createIPCServer(const std::string& config) {
    // 目前只支持Stdio实现
    // 后续可以根据config参数支持不同的IPC实现（如TCP Socket）
    return std::make_unique<StdioIPCServer>();
}

} // namespace ipc
} // namespace matcher