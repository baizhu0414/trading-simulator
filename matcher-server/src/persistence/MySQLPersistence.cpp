#include "persistence/MySQLPersistence.h"
#include "model/Order.h"
#include "model/Trade.h"
#include "util/Logger.h"
#include <iostream>
#include <chrono>
#include <sstream>
#include <regex>
#include <stdexcept>

namespace matcher
{
    namespace persistence
    {

        MySQLPersistence::MySQLPersistence(const std::string &connectionString)
            : port_(3306)
        {
            parseConnectionString(connectionString);
            // 立即尝试连接数据库，但不强制要求成功（记录结果）
            // 这样可以在程序启动时就验证数据库连接和创建表
            try
            {
                matcher::util::Logger::logDB("CONSTRUCTOR", "INFO", "MySQLPersistence构造函数调用，尝试连接数据库");
                if (connect())
                {
                    matcher::util::Logger::logDB("CONSTRUCTOR", "SUCCESS", "数据库连接和表创建成功");
                }
                else
                {
                    matcher::util::Logger::logDB("CONSTRUCTOR", "WARNING", "数据库连接失败，将在第一次操作时重试");
                }
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("CONSTRUCTOR", "ERROR", std::string("构造函数异常: ") + e.what());
                // 不抛出异常，允许延迟连接
            }
        }

        MySQLPersistence::~MySQLPersistence()
        {
            disconnect();
        }

        void MySQLPersistence::parseConnectionString(const std::string &connectionString)
        {
            // 解析格式: mysqlx://user:password@host:port/database
            std::regex pattern(R"(mysqlx?://([^:]+):([^@]+)@([^:]+):(\d+)/(.+))");
            std::smatch matches;

            if (std::regex_match(connectionString, matches, pattern))
            {
                user_ = matches[1].str();
                password_ = matches[2].str();
                host_ = matches[3].str();
                port_ = std::stoi(matches[4].str());
                database_ = matches[5].str();
            }
            else
            {
                throw std::invalid_argument("Invalid connection string format. Expected: mysqlx://user:password@host:port/database");
            }
        }

        bool MySQLPersistence::connect()
        {
            std::lock_guard<std::mutex> lock(connectionMutex_);

            if (connection_)
            {
                matcher::util::Logger::logDB("CONNECT", "SKIP", "数据库连接已存在，跳过连接");
                return true; // 已经连接
            }

            try
            {
                matcher::util::Logger::logDB("CONNECT", "START", "开始连接数据库: " + host_ + ":" + std::to_string(port_) + "/" + database_);

                // 初始化 MySQL 库（线程安全）
                matcher::util::Logger::logDB("MYSQL_INIT", "START", "初始化MySQL库");
                if (mysql_library_init(0, nullptr, nullptr) != 0)
                {
                    matcher::util::Logger::logDB("MYSQL_INIT", "FAILURE", "MySQL库初始化失败");
                    std::cerr << "[MySQLPersistence] Failed to initialize MySQL library" << std::endl;
                    return false;
                }
                matcher::util::Logger::logDB("MYSQL_INIT", "SUCCESS", "MySQL库初始化成功");

                // 初始化连接对象
                matcher::util::Logger::logDB("MYSQL_CONN_INIT", "START", "初始化MySQL连接对象");
                MYSQL *conn = mysql_init(nullptr);
                if (!conn)
                {
                    matcher::util::Logger::logDB("MYSQL_CONN_INIT", "FAILURE", "mysql_init失败");
                    std::cerr << "[MySQLPersistence] mysql_init failed" << std::endl;
                    return false;
                }
                matcher::util::Logger::logDB("MYSQL_CONN_INIT", "SUCCESS", "MySQL连接对象初始化成功");

                // 设置字符集为 UTF-8
                matcher::util::Logger::logDB("MYSQL_OPTIONS", "SET", "设置字符集为utf8mb4");
                mysql_options(conn, MYSQL_SET_CHARSET_NAME, "utf8mb4");

                // 建立连接
                matcher::util::Logger::logDB("MYSQL_CONNECT", "ATTEMPT", "尝试连接到数据库: " + host_ + ":" + std::to_string(port_));
                if (!mysql_real_connect(conn, host_.c_str(), user_.c_str(), password_.c_str(),
                                        database_.c_str(), port_, nullptr, 0))
                {
                    std::string error = mysql_error(conn);
                    matcher::util::Logger::logDB("MYSQL_CONNECT", "FAILURE", "数据库连接失败: " + error);
                    std::cerr << "[MySQLPersistence] Connection failed: " << error << std::endl;
                    mysql_close(conn);
                    return false;
                }

                connection_.reset(conn);
                matcher::util::Logger::logDB("MYSQL_CONNECT", "SUCCESS", "数据库连接成功: " + host_ + ":" + std::to_string(port_) + "/" + database_);
                std::cout << "[MySQLPersistence] Connected to " << host_ << ":" << port_
                          << "/" << database_ << std::endl;

                // 确保表存在
                matcher::util::Logger::logDB("ENSURE_TABLES", "START", "开始确保表存在");
                ensureTablesExist();
                matcher::util::Logger::logDB("ENSURE_TABLES", "COMPLETE", "表确保完成");

                return true;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("CONNECT", "EXCEPTION", "连接过程中发生异常: " + std::string(e.what()));
                std::cerr << "[MySQLPersistence] Connection exception: " << e.what() << std::endl;
                return false;
            }
        }

        void MySQLPersistence::disconnect()
        {
            std::lock_guard<std::mutex> lock(connectionMutex_);
            connection_.reset();
            mysql_library_end();
        }

        std::string MySQLPersistence::escapeString(const std::string &str)
        {
            if (!connection_)
            {
                throw std::runtime_error("Database connection not available");
            }

            std::vector<char> buffer(str.length() * 2 + 1);
            mysql_real_escape_string(connection_.get(), buffer.data(), str.c_str(), str.length());
            return std::string(buffer.data());
        }

        void MySQLPersistence::executeSQL(const std::string &sql)
        {
            if (!connection_)
            {
                throw std::runtime_error("Database connection not available");
            }

            if (mysql_query(connection_.get(), sql.c_str()) != 0)
            {
                std::string error = mysql_error(connection_.get());
                throw std::runtime_error("SQL execution failed: " + error + "\nSQL: " + sql);
            }
        }

        MYSQL *MySQLPersistence::getConnection()
        {
            if (!connection_)
            {
                throw std::runtime_error("Database connection not available");
            }
            return connection_.get();
        }

        void MySQLPersistence::saveTrade(const model::Trade &trade)
        {
            if (!connect())
            {
                throw std::runtime_error("Database connection failed");
            }

            try
            {
                matcher::util::Logger::logDB("SAVE_TRADE", "START", "开始保存成交: " + trade.tradeId);

                // 匹配 Java 端的 t_exchange_trade 表结构
                std::stringstream sql;
                sql << "INSERT INTO `t_exchange_trade` (`exec_id`, `buy_cl_order_id`, `sell_cl_order_id`, "
                    << "`security_id`, `exec_price`, `exec_qty`, `trade_time`, `market`, "
                    << "`buy_shareholder_id`, `sell_shareholder_id`) VALUES ('"
                    << escapeString(trade.tradeId) << "', '"
                    << escapeString(trade.clOrderIdBuy) << "', '"
                    << escapeString(trade.clOrderIdSell) << "', '"
                    << escapeString(trade.securityId) << "', "
                    << trade.price << ", "
                    << trade.quantity << ", "
                    << "NOW(), '" // trade_time 使用当前时间
                    << escapeString(trade.market) << "', '"
                    << escapeString(trade.buyShareholderId) << "', '"
                    << escapeString(trade.sellShareholderId) << "')";

                std::string sqlStr = sql.str();
                matcher::util::Logger::logDB("SAVE_TRADE", "EXECUTING", "执行SQL: " + sqlStr.substr(0, 100) + "...");
                executeSQL(sqlStr);

                matcher::util::Logger::logDB("SAVE_TRADE", "SUCCESS", "成交保存成功: " + trade.tradeId + ", 价格: " + std::to_string(trade.price) + ", 数量: " + std::to_string(trade.quantity));
                std::cout << "[MySQLPersistence] Saved trade: " << trade.tradeId << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("SAVE_TRADE", "FAILURE", "成交保存失败: " + trade.tradeId + ", 错误: " + std::string(e.what()));
                std::cerr << "[MySQLPersistence] Failed to save trade: " << e.what() << std::endl;
                throw;
            }
        }

        void MySQLPersistence::updateOrder(const model::Order &order)
        {
            if (!connect())
            {
                throw std::runtime_error("Database connection failed");
            }

            try
            {
                matcher::util::Logger::logDB("UPDATE_ORDER", "START", "开始更新订单: " + order.clOrderId + ", 状态: " + order.status + ", 数量: " + std::to_string(order.qty));

                // 匹配 Java 端的 t_exchange_order 表结构
                std::stringstream sql;
                sql << "UPDATE `t_exchange_order` SET `status` = '" << escapeString(order.status)
                    << "', `qty` = " << order.qty
                    << ", `version` = `version` + 1" // 乐观锁版本号自增
                    << " WHERE `cl_order_id` = '" << escapeString(order.clOrderId) << "'";

                std::string sqlStr = sql.str();
                matcher::util::Logger::logDB("UPDATE_ORDER", "EXECUTING", "执行SQL: " + sqlStr);
                executeSQL(sqlStr);

                matcher::util::Logger::logDB("UPDATE_ORDER", "SUCCESS", "订单更新成功: " + order.clOrderId + ", 新状态: " + order.status + ", 新数量: " + std::to_string(order.qty));
                std::cout << "[MySQLPersistence] Updated order: " << order.clOrderId << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("UPDATE_ORDER", "FAILURE", "订单更新失败: " + order.clOrderId + ", 错误: " + std::string(e.what()));
                std::cerr << "[MySQLPersistence] Failed to update order: " << e.what() << std::endl;
                throw;
            }
        }

        std::vector<model::Order> MySQLPersistence::loadUnfinishedOrders(const std::string &securityId)
        {
            if (!connect())
            {
                throw std::runtime_error("Database connection failed");
            }

            std::vector<model::Order> orders;

            try
            {
                matcher::util::Logger::logDB("LOAD_ORDERS", "START", "开始加载未完成订单: " + securityId);

                // 匹配 Java 端的 t_exchange_order 表结构
                std::stringstream sql;
                sql << "SELECT `cl_order_id`, `shareholder_id`, `market`, `security_id`, `side`, "
                    << "`qty`, `original_qty`, `price`, `status`, `create_time`, `version` "
                    << "FROM `t_exchange_order` WHERE `security_id` = '"
                    << escapeString(securityId) << "' AND `status` IN ('NEW', 'MATCHING', 'PART_FILLED') "
                    << "ORDER BY `create_time` ASC";

                std::string sqlStr = sql.str();
                matcher::util::Logger::logDB("LOAD_ORDERS", "EXECUTING", "执行SQL: " + sqlStr);
                executeSQL(sqlStr);

                // 使用 RAII 包装器自动管理资源
                struct ResultDeleter
                {
                    void operator()(MYSQL_RES *res) const
                    {
                        if (res)
                            mysql_free_result(res);
                    }
                };
                std::unique_ptr<MYSQL_RES, ResultDeleter> result(mysql_store_result(connection_.get()));

                if (!result)
                {
                    matcher::util::Logger::logDB("LOAD_ORDERS", "FAILURE", "获取查询结果失败: " + std::string(mysql_error(connection_.get())));
                    throw std::runtime_error(std::string("Failed to get result: ") + mysql_error(connection_.get()));
                }

                MYSQL_ROW row;
                int rowCount = 0;
                while ((row = mysql_fetch_row(result.get())))
                {
                    model::Order order;
                    order.clOrderId = row[0] ? row[0] : "";
                    order.shareholderId = row[1] ? row[1] : "";
                    order.market = row[2] ? row[2] : "";
                    order.securityId = row[3] ? row[3] : "";
                    order.side = row[4] ? row[4] : "";
                    order.qty = row[5] ? std::stoi(row[5]) : 0;
                    order.originalQty = row[6] ? std::stoi(row[6]) : 0;
                    order.price = row[7] ? std::stod(row[7]) : 0.0;
                    order.status = row[8] ? row[8] : "";
                    // create_time (row[9]) 和 version (row[10]) 暂不映射到 Order 结构体

                    orders.push_back(order);
                    rowCount++;
                }

                // result 会在作用域结束时自动释放

                matcher::util::Logger::logDB("LOAD_ORDERS", "SUCCESS", "成功加载 " + std::to_string(orders.size()) + " 个未完成订单，证券代码: " + securityId);
                std::cout << "[MySQLPersistence] Loaded " << orders.size()
                          << " unfinished orders for security: " << securityId << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("LOAD_ORDERS", "FAILURE", "加载未完成订单失败: " + securityId + ", 错误: " + std::string(e.what()));
                std::cerr << "[MySQLPersistence] Failed to load unfinished orders: " << e.what() << std::endl;
                throw;
            }

            return orders;
        }

        void MySQLPersistence::saveTrades(const std::vector<model::Trade> &trades)
        {
            if (trades.empty())
            {
                return;
            }

            if (!connect())
            {
                throw std::runtime_error("Database connection failed");
            }

            try
            {
                std::cout << "[MySQLPersistence] Saving " << trades.size() << " trades in batch" << std::endl;

                // 使用事务提高批量插入性能
                executeSQL("START TRANSACTION");

                try
                {
                    for (const auto &trade : trades)
                    {
                        saveTrade(trade);
                    }
                    executeSQL("COMMIT");
                }
                catch (...)
                {
                    executeSQL("ROLLBACK");
                    throw;
                }
            }
            catch (const std::exception &e)
            {
                std::cerr << "[MySQLPersistence] Failed to save trades batch: " << e.what() << std::endl;
                throw;
            }
        }

        void MySQLPersistence::updateOrders(const std::vector<model::Order> &orders)
        {
            if (orders.empty())
            {
                return;
            }

            if (!connect())
            {
                throw std::runtime_error("Database connection failed");
            }

            try
            {
                std::cout << "[MySQLPersistence] Updating " << orders.size() << " orders in batch" << std::endl;

                // 使用事务提高批量更新性能
                executeSQL("START TRANSACTION");

                try
                {
                    for (const auto &order : orders)
                    {
                        updateOrder(order);
                    }
                    executeSQL("COMMIT");
                }
                catch (...)
                {
                    executeSQL("ROLLBACK");
                    throw;
                }
            }
            catch (const std::exception &e)
            {
                std::cerr << "[MySQLPersistence] Failed to update orders batch: " << e.what() << std::endl;
                throw;
            }
        }

        void MySQLPersistence::ensureTablesExist()
        {
            try
            {
                matcher::util::Logger::logDB("CREATE_DATABASE", "START", "开始创建数据库: " + database_);

                // 创建数据库 (使用连接字符串中指定的数据库名)
                std::string createDatabase =
                    "CREATE DATABASE IF NOT EXISTS `" + escapeString(database_) + "` "
                                                                                  "DEFAULT CHARACTER SET utf8mb4 "
                                                                                  "DEFAULT COLLATE utf8mb4_unicode_ci";

                matcher::util::Logger::logDB("CREATE_DATABASE", "EXECUTING", "执行SQL: " + createDatabase);
                executeSQL(createDatabase);
                matcher::util::Logger::logDB("CREATE_DATABASE", "SUCCESS", "数据库创建成功: " + database_);

                std::string useDatabase = "USE `" + escapeString(database_) + "`";
                matcher::util::Logger::logDB("USE_DATABASE", "EXECUTING", "执行SQL: " + useDatabase);
                executeSQL(useDatabase);
                matcher::util::Logger::logDB("USE_DATABASE", "SUCCESS", "切换到数据库: " + database_);

                // 创建订单表 (完全匹配 Java 端的 t_exchange_order)
                std::string createOrdersTable = R"(
            CREATE TABLE IF NOT EXISTS `t_exchange_order` (
                `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
                `cl_order_id` varchar(16) NOT NULL COMMENT '订单唯一编号',
                `shareholder_id` varchar(10) NOT NULL COMMENT '股东号',
                `market` varchar(4) NOT NULL COMMENT '交易市场',
                `security_id` varchar(6) NOT NULL COMMENT '股票代码',
                `side` varchar(1) NOT NULL COMMENT '买卖方向',
                `qty` int UNSIGNED NOT NULL COMMENT '订单数量',
                `original_qty` int UNSIGNED NOT NULL COMMENT '原始订单数量',
                `price` decimal(10,2) NOT NULL COMMENT '订单价格',
                `status` varchar(20) NOT NULL COMMENT '订单状态',
                `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订单创建时间',
                `version` int(11) NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
                PRIMARY KEY (`id`),
                UNIQUE KEY `idx_cl_order_id` (`cl_order_id`),
                KEY `idx_status_create_time` (`status`,`create_time`),
                KEY `idx_securityid_status` (`security_id`, `status`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易所订单表'
        )";

                matcher::util::Logger::logDB("CREATE_TABLE", "EXECUTING", "创建订单表: t_exchange_order");
                executeSQL(createOrdersTable);
                matcher::util::Logger::logDB("CREATE_TABLE", "SUCCESS", "订单表创建成功: t_exchange_order");

                // 创建成交表 (完全匹配 Java 端的 t_exchange_trade)
                std::string createTradesTable = R"(
            CREATE TABLE IF NOT EXISTS `t_exchange_trade` (
                `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
                `exec_id` varchar(12) NOT NULL COMMENT '成交唯一编号',
                `buy_cl_order_id` varchar(16) NOT NULL COMMENT '买方订单唯一编号',
                `sell_cl_order_id` varchar(16) NOT NULL COMMENT '卖方订单唯一编号',
                `exec_qty` int UNSIGNED NOT NULL COMMENT '本次成交数量',
                `exec_price` decimal(10,2) NOT NULL COMMENT '本次成交价格',
                `trade_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '实际撮合成交时间',
                `market` VARCHAR(4) NOT NULL COMMENT '交易市场',
                `security_id` VARCHAR(6) NOT NULL COMMENT '股票代码',
                `buy_shareholder_id` VARCHAR(10) NOT NULL COMMENT '买方股东号',
                `sell_shareholder_id` VARCHAR(10) NOT NULL COMMENT '卖方股东号',
                PRIMARY KEY (`id`),
                UNIQUE KEY `idx_exec_id` (`exec_id`),
                KEY `idx_buy_cl_order_id` (`buy_cl_order_id`),
                KEY `idx_sell_cl_order_id` (`sell_cl_order_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易所成交明细表'
        )";

                matcher::util::Logger::logDB("CREATE_TABLE", "EXECUTING", "创建成交表: t_exchange_trade");
                executeSQL(createTradesTable);
                matcher::util::Logger::logDB("CREATE_TABLE", "SUCCESS", "成交表创建成功: t_exchange_trade");

                matcher::util::Logger::logDB("ENSURE_TABLES", "SUCCESS", "所有表已确保存在 (兼容Java schema)");
                std::cout << "[MySQLPersistence] Tables ensured to exist (compatible with Java schema)" << std::endl;
            }
            catch (const std::exception &e)
            {
                matcher::util::Logger::logDB("ENSURE_TABLES", "FAILURE", "确保表存在失败: " + std::string(e.what()));
                std::cerr << "[MySQLPersistence] Failed to ensure tables exist: " << e.what() << std::endl;
                throw;
            }
        }

    } // namespace persistence
} // namespace matcher
