#pragma once

#include "IPCServer.h"
#include <httplib.h>
#include <memory>
#include <string>
#include <functional>
#include <atomic>
#include <thread>
#include <mutex>

namespace matcher {
namespace ipc {

/**
 * @brief 基于 HTTP 的 IPC 服务器实现
 * 
 * 用于生产环境,通过 HTTP 协议与 Java 层通信:
 * - 接收: Java 通过 POST /match 发送撮合请求
 * - 发送: C++ 通过 HTTP POST 发送成交回报和持久化请求到 Java
 */
class HttpIPCServer : public IPCServer {
private:
    std::unique_ptr<httplib::Server> server_;
    std::unique_ptr<httplib::Client> client_;
    int port_;                                          ///< C++ 服务监听端口 (默认 9001)
    std::string javaHost_;                              ///< Java 服务地址
    int javaPort_;                                      ///< Java 服务端口 (默认 8081)
    std::function<void(model::Order)> orderCallback_;
    std::atomic<bool> running_{false};
    std::unique_ptr<std::thread> serverThread_;
    std::mutex callbackMutex_;

public:
    /**
     * @brief 构造函数
     * 
     * @param port C++ 服务监听端口 (默认 9001)
     * @param javaHost Java 服务地址 (默认 "localhost")
     * @param javaPort Java 服务端口 (默认 8081)
     */
    explicit HttpIPCServer(int port = 9001, 
                          const std::string& javaHost = "localhost", 
                          int javaPort = 8081);
    
    virtual ~HttpIPCServer();
    
    // IPCServer 接口实现
    virtual bool start() override;
    virtual void stop() override;
    virtual void setOrderCallback(std::function<void(model::Order)> callback) override;
    virtual bool sendExecutionReport(const model::Trade& trade) override;
    virtual bool sendCommand(const std::string& type, const std::string& payload) override;

private:
    /**
     * @brief 设置 HTTP 路由
     */
    void setupRoutes();
    
    /**
     * @brief 处理撮合请求 (POST /match)
     */
    void handleMatchRequest(const httplib::Request& req, httplib::Response& res);
    
    /**
     * @brief 处理健康检查 (GET /health)
     */
    void handleHealthCheck(const httplib::Request& req, httplib::Response& res);
    
    /**
     * @brief 解析订单 JSON
     */
    model::Order parseOrder(const nlohmann::json& orderJson);
};

} // namespace ipc
} // namespace matcher
