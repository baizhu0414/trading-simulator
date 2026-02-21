#pragma once

#include <memory>
#include <string>
#include <functional>
#include "model/Order.h"
#include "model/Trade.h"

namespace matcher {
namespace ipc {

/**
 * @brief IPC通信服务器抽象接口
 * 
 * 负责与Java层进行进程间通信，接收订单和发送成交回报。
 * 具体实现可以是标准输入/输出或TCP Socket。
 */
class IPCServer {
public:
    virtual ~IPCServer() = default;
    
    /**
     * @brief 启动IPC服务器
     * 
     * @return true 启动成功
     * @return false 启动失败
     */
    virtual bool start() = 0;
    
    /**
     * @brief 停止IPC服务器
     */
    virtual void stop() = 0;
    
    /**
     * @brief 设置订单接收回调函数
     * 
     * @param callback 当接收到新订单时调用的函数
     */
    virtual void setOrderCallback(std::function<void(model::Order)> callback) = 0;
    
    /**
     * @brief 发送成交回报
     * 
     * @param trade 成交记录
     * @return true 发送成功
     * @return false 发送失败
     */
    virtual bool sendExecutionReport(const model::Trade& trade) = 0;
    
    /**
     * @brief 发送通用命令给Java层
     * 
     * @param type 命令类型
     * @param payload JSON格式的有效载荷
     * @return true 发送成功
     * @return false 发送失败
     */
    virtual bool sendCommand(const std::string& type, const std::string& payload) = 0;
};

/**
 * @brief IPC服务器工厂函数
 * 
 * @param config 配置信息
 * @return std::unique_ptr<IPCServer> IPC服务器实例
 */
std::unique_ptr<IPCServer> createIPCServer(const std::string& config);

} // namespace ipc
} // namespace matcher