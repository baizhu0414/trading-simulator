-- 1. 新建数据库（指定字符集和排序规则，避免后续问题）
CREATE DATABASE IF NOT EXISTS trading_db
DEFAULT CHARACTER SET utf8mb4
DEFAULT COLLATE utf8mb4_unicode_ci;

-- 2. 切换到新库
USE trading_db;

-- 3. 建表（适配字段规则+约束）
CREATE TABLE IF NOT EXISTS `t_exchange_order` (
                                    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
                                    `cl_order_id` varchar(16) NOT NULL COMMENT '订单唯一编号（业务主键，固定16位）',
                                    `shareholder_id` varchar(10) NOT NULL COMMENT '股东号（固定10位）',
                                    `market` varchar(4) NOT NULL COMMENT '交易市场（仅XSHG/XSHE/BJSE）',
                                    `security_id` varchar(6) NOT NULL COMMENT '股票代码',
                                    `side` varchar(1) NOT NULL COMMENT '买卖方向（仅B/S）',
                                    `qty` int UNSIGNED NOT NULL COMMENT '订单数量（无符号32位整数，≥0，支持零股）',
                                    `original_qty` int UNSIGNED NOT NULL COMMENT '原始订单数量（创建时固定）',
                                    `price` decimal(10,2) NOT NULL COMMENT '订单价格（避免浮点精度问题）',
                                    `status` int NOT NULL COMMENT '订单状态',
                                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订单创建时间',
                                    `version` int(11) NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（解决并发修改）',

    -- 核心主键（InnoDB聚簇索引，必须保留）
                                    PRIMARY KEY (`id`),

    -- 业务唯一索引（核心：保证cl_order_id唯一性，适配单条订单查询/更新）
                                    UNIQUE KEY `uk_cl_order_id` (`cl_order_id`),

    -- 乐观锁更新优化索引（适配：WHERE cl_order_id = ? AND version = ?，避免回表）
                                    KEY `idx_cl_order_id_version` (`cl_order_id`, `version`),

    -- 状态+创建时间索引（适配：WHERE status IN (?) ORDER BY create_time ASC，避免filesort）
                                    KEY `idx_status_create_time` (`status`, `create_time`),

    -- 股票+状态索引（适配：撮合场景 WHERE security_id = ? AND status = ?）
                                    KEY `idx_security_id_status` (`security_id`, `status`),

    -- 原有业务约束（全部保留，保证数据合法性）
                                    CONSTRAINT `chk_cl_order_id_len` CHECK ((length(`cl_order_id`) = 16)),
                                    CONSTRAINT `chk_market` CHECK ((`market` in (_utf8mb4'XSHG',_utf8mb4'XSHE',_utf8mb4'BJSE'))),
                                    CONSTRAINT `chk_qty_unsigned` CHECK ((`qty` >= 0)),
                                    CONSTRAINT `chk_shareholder_id_len` CHECK ((length(`shareholder_id`) = 10)),
                                    CONSTRAINT `chk_side` CHECK ((`side` in (_utf8mb4'B',_utf8mb4'S')))
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易所订单表';