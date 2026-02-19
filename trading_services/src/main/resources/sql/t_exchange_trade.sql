-- 切换至交易数据库，与订单表保持一致
USE trading_db;

-- 交易所成交明细表（单条记录存双方订单ID，一次撮合仅插一条，贴合对敲撮合逻辑）
CREATE TABLE IF NOT EXISTS `t_exchange_trade` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    `exec_id` varchar(12) NOT NULL COMMENT '成交唯一编号（业务主键，固定12位，任务书execId）',
    `buy_cl_order_id` varchar(16) NOT NULL COMMENT '买方订单唯一编号（关联t_exchange_order.cl_order_id）',
    `sell_cl_order_id` varchar(16) NOT NULL COMMENT '卖方订单唯一编号（关联t_exchange_order.cl_order_id）',
    `exec_qty` int UNSIGNED NOT NULL COMMENT '本次成交数量（无符号32位，支持零股/整手，任务书execQty）',
    `exec_price` decimal(10,2) NOT NULL COMMENT '本次成交价格（与订单表精度一致，避浮点误差，任务书execPrice）',
    `trade_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '实际撮合成交时间',
    -- 新增字段
    `market` VARCHAR(4) NOT NULL COMMENT '交易市场（XSHG/XSHE/BJSE）',
    `security_id` VARCHAR(6) NOT NULL COMMENT '股票代码（6位）',
    `buy_shareholder_id` VARCHAR(10) NOT NULL COMMENT '买方股东号（10位）',
    `sell_shareholder_id` VARCHAR(10) NOT NULL COMMENT '卖方股东号（10位）',
    -- 主键和索引
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_exec_id` (`exec_id`),
    KEY `idx_buy_cl_order_id` (`buy_cl_order_id`),
    KEY `idx_sell_cl_order_id` (`sell_cl_order_id`)
    -- 移除MySQL低版本不支持的CHECK约束，改为业务层校验
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易所成交明细表（对敲撮合，单条记录存双方订单ID）';