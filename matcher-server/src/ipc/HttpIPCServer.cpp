#include "ipc/HttpIPCServer.h"
#include "model/Order.h"
#include "model/Trade.h"
#include <nlohmann/json.hpp>
#include <iostream>
#include <chrono>

using json = nlohmann::json;

namespace matcher {
namespace ipc {

HttpIPCServer::HttpIPCServer(int port, const std::string& javaHost, int javaPort)
    : port_(port)
    , javaHost_(javaHost)
    , javaPort_(javaPort) {
    server_ = std::make_unique<httplib::Server>();
    client_ = std::make_unique<httplib::Client>(javaHost_, javaPort_);
    
    // 设置超时时间
    client_->set_connection_timeout(5, 0);  // 5秒连接超时
    client_->set_read_timeout(10, 0);       // 10秒读取超时
    client_->set_write_timeout(10, 0);      // 10秒写入超时
}

HttpIPCServer::~HttpIPCServer() {
    stop();
}

bool HttpIPCServer::start() {
    if (running_) {
        std::cerr << "[HttpIPCServer] Already running" << std::endl;
        return false;
    }
    
    setupRoutes();
    running_ = true;
    
    // 在独立线程中启动 HTTP 服务器
    serverThread_ = std::make_unique<std::thread>([this]() {
        std::cout << "[HttpIPCServer] Starting on port " << port_ << std::endl;
        std::cout << "[HttpIPCServer] Java service at " << javaHost_ << ":" << javaPort_ << std::endl;
        
        if (!server_->listen("0.0.0.0", port_)) {
            std::cerr << "[HttpIPCServer] Failed to start server on port " << port_ << std::endl;
            running_ = false;
        }
    });
    
    // 等待服务器启动
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    
    if (running_) {
        std::cout << "[HttpIPCServer] Started successfully" << std::endl;
    }
    
    return running_;
}

void HttpIPCServer::stop() {
    if (!running_) {
        return;
    }
    
    std::cout << "[HttpIPCServer] Stopping..." << std::endl;
    running_ = false;
    
    if (server_) {
        server_->stop();
    }
    
    if (serverThread_ && serverThread_->joinable()) {
        serverThread_->join();
    }
    
    std::cout << "[HttpIPCServer] Stopped" << std::endl;
}

void HttpIPCServer::setOrderCallback(std::function<void(model::Order)> callback) {
    std::lock_guard<std::mutex> lock(callbackMutex_);
    orderCallback_ = std::move(callback);
}

bool HttpIPCServer::sendExecutionReport(const model::Trade& trade) {
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
            {"market", trade.market},
            {"buyShareholderId", trade.buyShareholderId},
            {"sellShareholderId", trade.sellShareholderId}
        };
        
        auto res = client_->Post("/trading/execution-report", j.dump(), "application/json");
        
        if (res && res->status == 200) {
            std::cout << "[HttpIPCServer] Sent execution report: " << trade.tradeId << std::endl;
            return true;
        } else {
            std::cerr << "[HttpIPCServer] Failed to send execution report. Status: " 
                      << (res ? std::to_string(res->status) : "No response") << std::endl;
            return false;
        }
    } catch (const std::exception& e) {
        std::cerr << "[HttpIPCServer] Exception sending execution report: " << e.what() << std::endl;
        return false;
    }
}

bool HttpIPCServer::sendCommand(const std::string& type, const std::string& payload) {
    try {
        json j;
        j["type"] = type;
        j["payload"] = json::parse(payload);
        
        auto res = client_->Post("/trading/command", j.dump(), "application/json");
        
        if (res && res->status == 200) {
            std::cout << "[HttpIPCServer] Sent command: " << type << std::endl;
            return true;
        } else {
            std::cerr << "[HttpIPCServer] Failed to send command " << type 
                      << ". Status: " << (res ? std::to_string(res->status) : "No response") << std::endl;
            return false;
        }
    } catch (const std::exception& e) {
        std::cerr << "[HttpIPCServer] Exception sending command: " << e.what() << std::endl;
        return false;
    }
}

void HttpIPCServer::setupRoutes() {
    // POST /match - 接收撮合请求
    server_->Post("/match", [this](const httplib::Request& req, httplib::Response& res) {
        handleMatchRequest(req, res);
    });
    
    // GET /health - 健康检查
    server_->Get("/health", [this](const httplib::Request& req, httplib::Response& res) {
        handleHealthCheck(req, res);
    });
    
    // POST /order - 接收单个订单
    server_->Post("/order", [this](const httplib::Request& req, httplib::Response& res) {
        try {
            auto j = json::parse(req.body);
            
            if (j.contains("type") && j["type"] == "NEW_ORDER" && j.contains("data")) {
                model::Order order = parseOrder(j["data"]);
                
                std::lock_guard<std::mutex> lock(callbackMutex_);
                if (orderCallback_) {
                    orderCallback_(std::move(order));
                }
                
                res.status = 200;
                res.set_content("{\"status\":\"ok\"}", "application/json");
            } else {
                res.status = 400;
                res.set_content("{\"error\":\"Invalid request format\"}", "application/json");
            }
        } catch (const std::exception& e) {
            res.status = 500;
            json error;
            error["error"] = e.what();
            res.set_content(error.dump(), "application/json");
        }
    });
}

void HttpIPCServer::handleMatchRequest(const httplib::Request& req, httplib::Response& res) {
    try {
        auto j = json::parse(req.body);
        
        // 解析撮合请求 (包含买单和卖单列表)
        std::string market = j["market"];
        std::string securityId = j["securityId"];
        
        // 解析买单列表
        std::vector<model::Order> buyOrders;
        if (j.contains("buyOrders")) {
            for (const auto& orderJson : j["buyOrders"]) {
                buyOrders.push_back(parseOrder(orderJson));
            }
        }
        
        // 解析卖单列表
        std::vector<model::Order> sellOrders;
        if (j.contains("sellOrders")) {
            for (const auto& orderJson : j["sellOrders"]) {
                sellOrders.push_back(parseOrder(orderJson));
            }
        }
        
        std::cout << "[HttpIPCServer] Received match request for " << securityId 
                  << " with " << buyOrders.size() << " buy orders and " 
                  << sellOrders.size() << " sell orders" << std::endl;
        
        // TODO: 调用撮合引擎处理
        // auto result = matchingEngine->match(market, securityId, buyOrders, sellOrders);
        
        // 暂时返回空结果
        json response;
        response["trades"] = json::array();
        response["remainingBuyOrders"] = json::array();
        response["remainingSellOrders"] = json::array();
        
        res.status = 200;
        res.set_content(response.dump(), "application/json");
        
    } catch (const std::exception& e) {
        std::cerr << "[HttpIPCServer] Error handling match request: " << e.what() << std::endl;
        res.status = 500;
        json error;
        error["error"] = e.what();
        res.set_content(error.dump(), "application/json");
    }
}

void HttpIPCServer::handleHealthCheck(const httplib::Request& req, httplib::Response& res) {
    json health;
    health["status"] = "ok";
    health["service"] = "matcher-server";
    health["port"] = port_;
    health["running"] = running_.load();
    
    res.status = 200;
    res.set_content(health.dump(), "application/json");
}

model::Order HttpIPCServer::parseOrder(const nlohmann::json& orderJson) {
    model::Order order;
    
    order.clOrderId = orderJson.value("clOrderId", "");
    order.shareholderId = orderJson.value("shareholderId", "");
    order.market = orderJson.value("market", "");
    order.securityId = orderJson.value("securityId", "");
    order.side = orderJson.value("side", "");
    order.qty = orderJson.value("qty", 0);
    order.originalQty = orderJson.value("original_qty", order.qty);
    order.price = orderJson.value("price", 0.0);
    order.status = orderJson.value("status", "NEW");
    
    // 解析时间戳
    if (orderJson.contains("timestamp")) {
        int64_t timestamp_ms = orderJson["timestamp"];
        order.timestamp = std::chrono::system_clock::time_point(
            std::chrono::milliseconds(timestamp_ms));
    } else {
        order.timestamp = std::chrono::system_clock::now();
    }
    
    return order;
}

} // namespace ipc
} // namespace matcher
