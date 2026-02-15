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
                                    `price` decimal(10,2) NOT NULL COMMENT '订单价格（避免浮点精度问题）',
                                    `status` varchar(20) NOT NULL COMMENT '订单状态',
                                    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订单创建时间',
                                    `version` int(11) NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（解决并发修改）',
                                    PRIMARY KEY (`id`),
                                    UNIQUE KEY `idx_cl_order_id` (`cl_order_id`), -- 幂等校验索引
                                    KEY `idx_status_create_time` (`status`,`create_time`), -- 恢复查询索引
    -- 字段规则约束（MySQL 8.0+支持，增强数据合法性）
                                    CONSTRAINT `chk_cl_order_id_len` CHECK (LENGTH(`cl_order_id`) = 16),
                                    CONSTRAINT `chk_shareholder_id_len` CHECK (LENGTH(`shareholder_id`) = 10),
                                    CONSTRAINT `chk_market` CHECK (`market` IN ('XSHG', 'XSHE', 'BJSE')),
                                    CONSTRAINT `chk_side` CHECK (`side` IN ('B', 'S')),
                                    CONSTRAINT `chk_qty_unsigned` CHECK (`qty` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易所订单表';


-- 新增字段，实现部分成交功能
USE trading_db;

-- 新增original_qty字段（原始订单数量）
ALTER TABLE `t_exchange_order`
    ADD COLUMN `original_qty` int UNSIGNED NOT NULL COMMENT '原始订单数量（创建时固定）' AFTER `qty`;

-- 初始化历史数据：original_qty = qty（保证存量数据兼容）
UPDATE `t_exchange_order` SET `original_qty` = `qty` WHERE `original_qty` IS NULL;

-- 新增索引（可选，优化部分成交订单查询）
ALTER TABLE `t_exchange_order` ADD KEY `idx_securityid_status` (`security_id`, `status`);