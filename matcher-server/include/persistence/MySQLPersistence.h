#pragma once

#include "IPersistence.h"
#include <mysql/mysql.h>
#include <memory>
#include <string>
#include <mutex>

namespace matcher
{
    namespace persistence
    {

        /**
         * @brief MySQL直连持久化实现
         *
         * 用于测试环境，直接连接本地MySQL数据库。
         * 使用 libmysql C API 提供轻量级的数据库访问。
         * 提供订单和成交记录的直接数据库操作。
         */
        class MySQLPersistence : public IPersistence
        {
        private:
            struct MySQLDeleter
            {
                void operator()(MYSQL *conn)
                {
                    if (conn)
                    {
                        mysql_close(conn);
                    }
                }
            };

            std::unique_ptr<MYSQL, MySQLDeleter> connection_;
            std::string host_;
            std::string user_;
            std::string password_;
            std::string database_;
            unsigned int port_;
            std::mutex connectionMutex_;

        public:
            /**
             * @brief 构造函数
             *
             * @param connectionString MySQL连接字符串 (格式: mysqlx://user:password@host:port/database)
             */
            explicit MySQLPersistence(const std::string &connectionString);

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
            virtual void saveTrade(const model::Trade &trade) override;
            virtual void updateOrder(const model::Order &order) override;
            virtual std::vector<model::Order> loadUnfinishedOrders(const std::string &securityId) override;
            virtual void saveTrades(const std::vector<model::Trade> &trades) override;
            virtual void updateOrders(const std::vector<model::Order> &orders) override;

        private:
            /**
             * @brief 解析连接字符串
             *
             * @param connectionString 连接字符串
             */
            void parseConnectionString(const std::string &connectionString);

            /**
             * @brief 确保数据库表存在
             */
            void ensureTablesExist();

            /**
             * @brief 执行SQL语句
             *
             * @param sql SQL语句
             */
            void executeSQL(const std::string &sql);

            /**
             * @brief 转义字符串防止SQL注入
             *
             * @param str 原始字符串
             * @return std::string 转义后的字符串
             */
            std::string escapeString(const std::string &str);

            /**
             * @brief 获取数据库连接
             *
             * @return MYSQL* 数据库连接指针
             */
            MYSQL *getConnection();
        };

    } // namespace persistence
} // namespace matcher
