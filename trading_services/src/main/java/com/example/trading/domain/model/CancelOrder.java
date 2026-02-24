package com.example.trading.domain.model;

import com.example.trading.common.enums.SideEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 撤单请求领域模型（对齐任务书3.2规范）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelOrder implements Serializable {
    /** 撤单唯一编号（固定16位） */
    private String clOrderId;
    /** 待撤原订单唯一编号（固定16位） */
    private String origClOrderId;
    /** 交易市场（XSHG/XSHE/BJSE） */
    private String market;
    /** 股票代码（固定6位） */
    private String securityId;
    /** 股东号（固定10位） */
    private String shareholderId;
    /** 买卖方向（B/S） */
    private SideEnum side;
    /** 撤单请求创建时间 */
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();
}