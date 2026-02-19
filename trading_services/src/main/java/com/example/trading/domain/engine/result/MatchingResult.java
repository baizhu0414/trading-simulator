package com.example.trading.domain.engine.result;

import com.example.trading.domain.model.Order;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

import java.util.ArrayList;

/**
 * 撮合结果封装类(1:n的对应结构进行订单撮合)
 */


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingResult {
    private Order matchedOrder; // 当前订单（撮合后更新剩余数量）
    @Builder.Default
    private List<MatchCounterDetail> matchDetails = new ArrayList<>(); // 所有匹配细节列表

    // 【核心优化】单笔匹配细节封装，避免多List索引错位
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MatchCounterDetail {
        private Order counterPartyOrder; // 单笔对手方订单
        private Integer execQty; // 单笔成交数量
        private BigDecimal execPrice; // 单笔成交价格
    }

    // 便捷方法：添加单笔匹配细节
    public void addMatchDetail(Order counterPartyOrder, Integer execQty, BigDecimal execPrice) {
        if (this.matchDetails == null) {
            this.matchDetails = new ArrayList<>();
        }
        this.matchDetails.add(new MatchCounterDetail(counterPartyOrder, execQty, execPrice));
    }
}