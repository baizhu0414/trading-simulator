#pragma once

#include <memory>
#include <string>
#include <vector>
#include "model/Order.h"
#include "model/Trade.h"
#include "persistence/IPersistence.h"
#include "ipc/IPCServer.h"

namespace matcher {
namespace core {

/**
 * @brief 撮合引擎抽象接口
 * 
 * 定义了撮合引擎的核心功能契约。
 * 具体实现由负责撮合算法的开发者完成。
 */
class IMatchingEngine {
public:
    virtual ~IMatchingEngine() = default;
    
    /**
     * @brief 提交新订单到撮合引擎
     * 
     * @param order 订单对象
     * 
     * 该方法会:
     * 1. 将订单加入对应的订单簿
     * 2. 触发撮合逻辑
     * 3. 生成成交记录(如果有成交)
     */
    virtual void submitOrder(const model::Order& order) = 0;
    
    /**
     * @brief 撤销订单
     * 
     * @param clOrderId 订单ID
     * @return true 撤销成功
     * @return false 订单不存在或已成交
     */
    virtual bool cancelOrder(const std::string& clOrderId) = 0;
    
    /**
     * @brief 批量撮合接口 (供 HTTP IPC 调用)
     * 
     * @param market 市场代码 (XSHG/XSHE/BJSE)
     * @param securityId 证券代码
     * @param buyOrders 买单列表
     * @param sellOrders 卖单列表
     * @return std::vector<model::Trade> 成交记录列表
     * 
     * 用于集合竞价或批量撮合场景
     */
    virtual std::vector<model::Trade> matchBatch(
        const std::string& market,
        const std::string& securityId,
        const std::vector<model::Order>& buyOrders,
        const std::vector<model::Order>& sellOrders) = 0;
    
    /**
     * @brief 查询订单簿快照 (可选,用于调试)
     * 
     * @param securityId 证券代码
     * @return std::vector<model::Order> 当前未成交订单列表
     */
    virtual std::vector<model::Order> getOrderBook(
        const std::string& securityId) const = 0;
};

/**
 * @brief 撮合引擎工厂函数声明
 * 
 * @param persistence 持久化实例 (注入依赖)
 * @param ipcServer IPC服务器实例 (用于发送成交回报)
 * @return std::unique_ptr<IMatchingEngine> 撮合引擎实例
 * 
 * 注意: 该函数的实现由负责撮合引擎的开发者提供
 */
std::unique_ptr<IMatchingEngine> createMatchingEngine(
    std::unique_ptr<persistence::IPersistence> persistence,
    ipc::IPCServer* ipcServer);

} // namespace core
} // namespace matcher
