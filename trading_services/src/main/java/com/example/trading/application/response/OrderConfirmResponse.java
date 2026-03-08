package com.example.trading.application.response;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * 未成交时返回：订单确认回报（任务书3.4节格式）
 */
@Data
@SuperBuilder
public class OrderConfirmResponse extends BaseResponse {
    private String clOrderId;
    private String market;
    private String securityId;
    private String side; // B/S
    private Integer qty;
    private BigDecimal price;
    private String shareholderId;
    private String orderStatus; // 订单状态描述
}

