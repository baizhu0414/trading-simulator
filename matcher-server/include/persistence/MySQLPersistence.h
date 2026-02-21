#pragma once

#include "IPersistence.h"
#include <mysqlx/xdevapi.h>
#include <memory>
#include <string>
#include <mutex>

namespace matcher {
namespace persistence {

/**
 * @brief MySQL直连持久化实现
 * 
 * 用于测试环境，直接连接本地MySQL数据库。
 * 提供订单和成交记录的直接数据库操作。
 */
class MySQLPersistence : public IPersistence {
private:
    std::unique_ptr<mysqlx::Session> session_;
    std::string connectionString_;
    std::mutex connectionMutex_;
    
public:
    /**
     * @brief 构造函数
     * 
     * @param connectionString MySQL连接字符串
     */
    explicit MySQLPersistence(const std::string& connectionString);
    
    virtual ~MySQLPersistence();
    
    /**
     * @brief 连接数据库
     * 
     * @return true 连接成功
     * @return false 连接失败
     */
    bool connect();
    
    /**
     * @brief 断开数据库连接
     */
    void disconnect();
    
    // IPersistence接口实现
    virtual void saveTrade(const model::Trade& trade) override;
    virtual void updateOrder(const model::Order& order) override;
    virtual std::vector<model::Order> loadUnfinishedOrders(const std::string& securityId) override;
    virtual void saveTrades(const std::vector<model::Trade>& trades) override;
    virtual void updateOrders(const std::vector<model::Order>& orders) override;
    
private:
    /**
     * @brief 确保数据库表存在
     */
    void ensureTablesExist();
    
    /**
     * @brief 执行SQL语句
     * 
     * @param sql SQL语句
     */
    void executeSQL(const std::string& sql);
    
    /**
     * @brief 获取数据库会话
     * 
     * @return mysqlx::Session& 数据库会话引用
     */
    mysqlx::Session& getSession();
};

} // namespace persistence
} // namespace matcher