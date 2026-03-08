#pragma once

#include <vector>
#include <string>
#include "model/Order.h"
#include "model/Trade.h"

namespace matcher {
namespace persistence {

/**
 * @brief 数据持久化抽象接口
 * 
 * 采用策略模式，支持不同的持久化实现：
 * - MySQLPersistence: 本地直连MySQL，用于测试环境
 * - ProxyPersistence: 通过IPC发送给Java层，用于生产环境
 */
class IPersistence {
public:
    virtual ~IPersistence() = default;
    
    /**
     * @brief 保存成交记录
     * 
     * @param trade 成交记录
     */
    virtual void saveTrade(const model::Trade& trade) = 0;
    
    /**
     * @brief 更新订单状态
     * 
     * @param order 订单（包含更新后的状态）
     */
    virtual void updateOrder(const model::Order& order) = 0;
    
    /**
     * @brief 加载未完成的订单
     * 
     * @param securityId 证券代码
     * @return std::vector<model::Order> 未完成订单列表
     */
    virtual std::vector<model::Order> loadUnfinishedOrders(const std::string& securityId) = 0;
    
    /**
     * @brief 批量保存成交记录
     * 
     * @param trades 成交记录列表
     */
    virtual void saveTrades(const std::vector<model::Trade>& trades) = 0;
    
    /**
     * @brief 批量更新订单状态
     * 
     * @param orders 订单列表
     */
    virtual void updateOrders(const std::vector<model::Order>& orders) = 0;
};

} // namespace persistence
} // namespace matcher