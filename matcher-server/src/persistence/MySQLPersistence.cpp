#include "persistence/MySQLPersistence.h"
#include "model/Order.h"
#include "model/Trade.h"
#include <iostream>
#include <chrono>
#include <sstream>
#include <regex>
#include <stdexcept>

namespace matcher {
namespace persistence {

MySQLPersistence::MySQLPersistence(const std::string& connectionString)
    : port_(3306) {
    parseConnectionString(connectionString);
}

MySQLPersistence::~MySQLPersistence() {
    disconnect();
}

void MySQLPersistence::parseConnectionString(const std::string& connectionString) {
    // 解析格式: mysqlx://user:password@host:port/database
    std::regex pattern(R"(mysqlx?://([^:]+):([^@]+)@([^:]+):(\d+)/(.+))");
    std::smatch matches;
    
    if (std::regex_match(connectionString, matches, pattern)) {
        user_ = matches[1].str();
        password_ = matches[2].str();
        host_ = matches[3].str();
        port_ = std::stoi(matches[4].str());
        database_ = matches[5].str();
    } else {
        throw std::invalid_argument("Invalid connection string format. Expected: mysqlx://user:password@host:port/database");
    }
}

bool MySQLPersistence::connect() {
    std::lock_guard<std::mutex> lock(connectionMutex_);
    
    if (connection_) {
        return true; // 已经连接
    }
    
    try {
        // 初始化 MySQL 库（线程安全）
        if (mysql_library_init(0, nullptr, nullptr) != 0) {
            std::cerr << "[MySQLPersistence] Failed to initialize MySQL library" << std::endl;
            return false;
        }
        
        // 初始化连接对象
        MYSQL* conn = mysql_init(nullptr);
        if (!conn) {
            std::cerr << "[MySQLPersistence] mysql_init failed" << std::endl;
            return false;
        }
        
        // 设置字符集为 UTF-8
        mysql_options(conn, MYSQL_SET_CHARSET_NAME, "utf8mb4");
        
        // 建立连接
        if (!mysql_real_connect(conn, host_.c_str(), user_.c_str(), password_.c_str(),
                                database_.c_str(), port_, nullptr, 0)) {
            std::string error = mysql_error(conn);
            std::cerr << "[MySQLPersistence] Connection failed: " << error << std::endl;
            mysql_close(conn);
            return false;
        }
        
        connection_.reset(conn);
        std::cout << "[MySQLPersistence] Connected to " << host_ << ":" << port_ 
                  << "/" << database_ << std::endl;
        
        // 确保表存在
        ensureTablesExist();
        
        return true;
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Connection exception: " << e.what() << std::endl;
        return false;
    }
}

void MySQLPersistence::disconnect() {
    std::lock_guard<std::mutex> lock(connectionMutex_);
    connection_.reset();
    mysql_library_end();
}

std::string MySQLPersistence::escapeString(const std::string& str) {
    if (!connection_) {
        throw std::runtime_error("Database connection not available");
    }
    
    std::vector<char> buffer(str.length() * 2 + 1);
    mysql_real_escape_string(connection_.get(), buffer.data(), str.c_str(), str.length());
    return std::string(buffer.data());
}

void MySQLPersistence::executeSQL(const std::string& sql) {
    if (!connection_) {
        throw std::runtime_error("Database connection not available");
    }
    
    if (mysql_query(connection_.get(), sql.c_str()) != 0) {
        std::string error = mysql_error(connection_.get());
        throw std::runtime_error("SQL execution failed: " + error + "\nSQL: " + sql);
    }
}

MYSQL* MySQLPersistence::getConnection() {
    if (!connection_) {
        throw std::runtime_error("Database connection not available");
    }
    return connection_.get();
}

void MySQLPersistence::saveTrade(const model::Trade& trade) {
    if (!connect()) {
        throw std::runtime_error("Database connection failed");
    }
    
    try {
        std::stringstream sql;
        sql << "INSERT INTO trades (trade_id, cl_order_id_buy, cl_order_id_sell, "
            << "security_id, price, quantity, timestamp) VALUES ('"
            << escapeString(trade.tradeId) << "', '"
            << escapeString(trade.clOrderIdBuy) << "', '"
            << escapeString(trade.clOrderIdSell) << "', '"
            << escapeString(trade.securityId) << "', "
            << trade.price << ", "
            << trade.quantity << ", "
            << std::chrono::duration_cast<std::chrono::milliseconds>(
                trade.timestamp.time_since_epoch()).count()
            << ")";
        
        executeSQL(sql.str());
        
        std::cout << "[MySQLPersistence] Saved trade: " << trade.tradeId << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Failed to save trade: " << e.what() << std::endl;
        throw;
    }
}

void MySQLPersistence::updateOrder(const model::Order& order) {
    if (!connect()) {
        throw std::runtime_error("Database connection failed");
    }
    
    try {
        std::stringstream sql;
        sql << "UPDATE orders SET status = '" << escapeString(order.status) 
            << "', executed_quantity = " << order.executedQuantity
            << ", last_executed_price = " << order.lastExecutedPrice
            << ", last_updated = " << std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count()
            << " WHERE cl_order_id = '" << escapeString(order.clOrderId) << "'";
        
        executeSQL(sql.str());
        
        std::cout << "[MySQLPersistence] Updated order: " << order.clOrderId << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Failed to update order: " << e.what() << std::endl;
        throw;
    }
}

std::vector<model::Order> MySQLPersistence::loadUnfinishedOrders(const std::string& securityId) {
    if (!connect()) {
        throw std::runtime_error("Database connection failed");
    }
    
    std::vector<model::Order> orders;
    
    try {
        std::stringstream sql;
        sql << "SELECT cl_order_id, security_id, side, price, quantity, "
            << "executed_quantity, last_executed_price, status, order_type, "
            << "time_in_force, timestamp FROM orders WHERE security_id = '" 
            << escapeString(securityId) << "' AND status IN ('NEW', 'PARTIALLY_FILLED')";
        
        executeSQL(sql.str());
        
        MYSQL_RES* result = mysql_store_result(connection_.get());
        if (!result) {
            throw std::runtime_error(std::string("Failed to get result: ") + mysql_error(connection_.get()));
        }
        
        MYSQL_ROW row;
        while ((row = mysql_fetch_row(result))) {
            model::Order order;
            order.clOrderId = row[0] ? row[0] : "";
            order.securityId = row[1] ? row[1] : "";
            order.side = row[2] ? row[2] : "";
            order.price = row[3] ? std::stod(row[3]) : 0.0;
            order.quantity = row[4] ? std::stoll(row[4]) : 0;
            order.executedQuantity = row[5] ? std::stoll(row[5]) : 0;
            order.lastExecutedPrice = row[6] ? std::stod(row[6]) : 0.0;
            order.status = row[7] ? row[7] : "";
            order.orderType = row[8] ? row[8] : "";
            order.timeInForce = row[9] ? row[9] : "";
            
            if (row[10]) {
                int64_t timestamp_ms = std::stoll(row[10]);
                order.timestamp = std::chrono::system_clock::time_point(
                    std::chrono::milliseconds(timestamp_ms));
            }
            
            orders.push_back(order);
        }
        
        mysql_free_result(result);
        
        std::cout << "[MySQLPersistence] Loaded " << orders.size() 
                  << " unfinished orders for security: " << securityId << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Failed to load unfinished orders: " << e.what() << std::endl;
        throw;
    }
    
    return orders;
}

void MySQLPersistence::saveTrades(const std::vector<model::Trade>& trades) {
    if (trades.empty()) {
        return;
    }
    
    if (!connect()) {
        throw std::runtime_error("Database connection failed");
    }
    
    try {
        std::cout << "[MySQLPersistence] Saving " << trades.size() << " trades in batch" << std::endl;
        
        // 使用事务提高批量插入性能
        executeSQL("START TRANSACTION");
        
        try {
            for (const auto& trade : trades) {
                saveTrade(trade);
            }
            executeSQL("COMMIT");
        } catch (...) {
            executeSQL("ROLLBACK");
            throw;
        }
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Failed to save trades batch: " << e.what() << std::endl;
        throw;
    }
}

void MySQLPersistence::updateOrders(const std::vector<model::Order>& orders) {
    if (orders.empty()) {
        return;
    }
    
    if (!connect()) {
        throw std::runtime_error("Database connection failed");
    }
    
    try {
        std::cout << "[MySQLPersistence] Updating " << orders.size() << " orders in batch" << std::endl;
        
        // 使用事务提高批量更新性能
        executeSQL("START TRANSACTION");
        
        try {
            for (const auto& order : orders) {
                updateOrder(order);
            }
            executeSQL("COMMIT");
        } catch (...) {
            executeSQL("ROLLBACK");
            throw;
        }
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Failed to update orders batch: " << e.what() << std::endl;
        throw;
    }
}

void MySQLPersistence::ensureTablesExist() {
    try {
        // 创建 trades 表
        std::string createTradesTable = R"(
            CREATE TABLE IF NOT EXISTS trades (
                trade_id VARCHAR(64) PRIMARY KEY,
                cl_order_id_buy VARCHAR(64) NOT NULL,
                cl_order_id_sell VARCHAR(64) NOT NULL,
                security_id VARCHAR(20) NOT NULL,
                price DECIMAL(20, 8) NOT NULL,
                quantity BIGINT NOT NULL,
                timestamp BIGINT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_security_timestamp (security_id, timestamp)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        )";
        
        // 创建 orders 表
        std::string createOrdersTable = R"(
            CREATE TABLE IF NOT EXISTS orders (
                cl_order_id VARCHAR(64) PRIMARY KEY,
                security_id VARCHAR(20) NOT NULL,
                side VARCHAR(10) NOT NULL,
                price DECIMAL(20, 8) NOT NULL,
                quantity BIGINT NOT NULL,
                executed_quantity BIGINT DEFAULT 0,
                last_executed_price DECIMAL(20, 8),
                status VARCHAR(20) NOT NULL,
                order_type VARCHAR(20),
                time_in_force VARCHAR(20),
                timestamp BIGINT NOT NULL,
                last_updated BIGINT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_security_status (security_id, status),
                INDEX idx_timestamp (timestamp)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        )";
        
        executeSQL(createTradesTable);
        executeSQL(createOrdersTable);
        
        std::cout << "[MySQLPersistence] Tables ensured to exist" << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "[MySQLPersistence] Failed to ensure tables exist: " << e.what() << std::endl;
        throw;
    }
}

} // namespace persistence
} // namespace matcher
