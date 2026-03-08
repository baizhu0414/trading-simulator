package com.example.trading.domain.event;

import com.example.trading.common.enums.PersistSignalType;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import lombok.Data;

import java.util.List;

/**
 * 持久化信号（统一封装所有持久化请求）
 */
@Data
public class PersistSignal {
    // 基础字段
    private String bizId; // 业务ID（订单号/批次号）
    private PersistSignalType signalType; // 信号类型
    private String processingKey; // Redis处理Key（用于后续标记DONE）

    // 不同场景的业务数据（按需赋值）
    private Order matchedOrder; // 订单+交易场景
    private List<Order> counterOrders; // 订单+交易场景
    private List<Trade> trades; // 订单+交易场景
    private Order canceledOrder; // 撤单场景
    private List<MatchingEngine.RecoveryMatchResult> recoveryResults; // 恢复结果场景
}