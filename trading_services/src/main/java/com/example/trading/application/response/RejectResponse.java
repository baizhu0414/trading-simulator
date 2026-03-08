package com.example.trading.application.response;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 拒绝回报（优化版，符合任务书3.5节格式）
 */
@Data
@SuperBuilder
public class RejectResponse extends BaseResponse {
    private String clOrderId = "";
    private String market = "";
    private String securityId = "";
    private String side = "";
    private Integer qty = 0;
    private BigDecimal price = BigDecimal.ZERO;
    private String shareholderId = "";
}


