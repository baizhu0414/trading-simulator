package com.example.trading.domain.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成交表实体
 * 每笔交易只生成一条交易记录，包含买卖双方的信息和成交信息。
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Trade {
    private Long id; // 自增主键
    @EqualsAndHashCode.Include
    private String execId; // 成交唯一编号（12位）
    private String buyClOrderId; // 买方订单ID
    private String sellClOrderId; // 卖方订单ID
    private Integer execQty; // 本次成交数量（100的整数倍）
    private BigDecimal execPrice; // 本次成交价格
    private LocalDateTime tradeTime; // 成交时间
    // 新增：任务书成交回报要求的关联字段
    private String market; // 交易市场（XSHG/XSHE/BJSE）
    private String securityId; // 股票代码（6位）
    private String buyShareholderId; // 买方股东号（10位）
    private String sellShareholderId; // 卖方股东号（10位）
}