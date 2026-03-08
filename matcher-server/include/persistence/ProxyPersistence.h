#pragma once

#include "IPersistence.h"
#include "ipc/IPCServer.h"
#include <memory>
#include <string>

namespace matcher {
namespace persistence {

/**
 * @brief 远程代理持久化实现
 * 
 * 用于生产环境，不直接连接数据库，
 * 而是通过IPC将数据变更请求发送给Java层处理。
 */
class ProxyPersistence : public IPersistence {
private:
    ipc::IPCServer* ipcServer_;
    
public:
    /**
     * @brief 构造函数
     * 
     * @param ipcServer IPC服务器实例（用于发送数据到Java层）
     */
    explicit ProxyPersistence(ipc::IPCServer* ipcServer);
    
    // IPersistence接口实现
    virtual void saveTrade(const model::Trade& trade) override;
    virtual void updateOrder(const model::Order& order) override;
    virtual std::vector<model::Order> loadUnfinishedOrders(const std::string& securityId) override;
    virtual void saveTrades(const std::vector<model::Trade>& trades) override;
    virtual void updateOrders(const std::vector<model::Order>& orders) override;
    
private:
    /**
     * @brief 发送JSON命令到Java层
     * 
     * @param type 命令类型
     * @param data JSON格式的数据
     * @return true 发送成功
     * @return false 发送失败
     */
    bool sendCommand(const std::string& type, const std::string& data);
};

} // namespace persistence
} // namespace matcher