package com.example.trading.application.response;

import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 成交回报,包括：部分/完全成交时返回
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TradeResponse extends BaseResponse {
    private String clOrderId;
    private String market;
    private String securityId;
    private String side; // B/S
    private Integer orderQty; // 委托数量
    private BigDecimal orderPrice; // 委托价格
    private String shareholderId;
    private String orderStatus;

    // 完全仿照MatchingResult：使用@Builder.Default初始化空列表
    @Builder.Default
    private List<TradeCounterResponse> tradeResponses = new ArrayList<>();

    // 完全仿照MatchCounterDetail：仅保留@Data+@AllArgsConstructor+@NoArgsConstructor
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TradeCounterResponse {
        private String execId; // 成交唯一编号
        private Integer execQty; // 本次成交数量
        private BigDecimal execPrice; // 本次成交价格
        private LocalDateTime execTime; // 成交时间
        private String orderStatus; // 对手订单最终状态
    }

    // 仿照addMatchDetail：新增便捷添加单笔对手方成交详情的方法
    public void addTradeResponse(String execId, Integer execQty, BigDecimal execPrice,
                                 LocalDateTime execTime, String orderStatus) {
        if (this.tradeResponses == null) {
            this.tradeResponses = new ArrayList<>();
        }
        this.tradeResponses.add(new TradeCounterResponse(execId, execQty, execPrice, execTime, orderStatus));
    }

    // 重载方法：支持直接传入TradeCounterResponse对象（可选，提升灵活性）
    public void addTradeResponse(TradeCounterResponse tradeCounterResponse) {
        if (this.tradeResponses == null) {
            this.tradeResponses = new ArrayList<>();
        }
        this.tradeResponses.add(tradeCounterResponse);
    }
}

