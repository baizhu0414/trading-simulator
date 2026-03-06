package com.example.trading.domain.event;

import com.example.trading.common.enums.PersistSignalType;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 持久化信号工厂（统一创建信号，解耦创建逻辑）
 */
@Component
public class PersistSignalFactory {
    /**
     * 创建「订单+交易」持久化信号（对应原有persistOrderAndTrades）
     */
    public PersistSignal createOrderAndTradesSignal(
            Order matchedOrder, List<Order> counterOrders, List<Trade> trades,
            String processingKey) {
        PersistSignal signal = new PersistSignal();
        signal.setBizId(matchedOrder.getClOrderId());
        signal.setSignalType(PersistSignalType.ORDER_AND_TRADES);
        signal.setProcessingKey(processingKey);
        signal.setMatchedOrder(matchedOrder);
        signal.setCounterOrders(counterOrders);
        signal.setTrades(trades);
        return signal;
    }

    /**
     * 创建「撤单」持久化信号（对应原有persistCancel）
     */
    public PersistSignal createCancelOrderSignal(Order canceledOrder, String processingKey) {
        PersistSignal signal = new PersistSignal();
        signal.setBizId(canceledOrder.getClOrderId());
        signal.setSignalType(PersistSignalType.CANCEL_ORDER);
        signal.setProcessingKey(processingKey);
        signal.setCanceledOrder(canceledOrder);
        return signal;
    }

    /**
     * 创建「恢复结果」持久化信号（对应原有persistRecoveryResults）
     */
    public PersistSignal createRecoveryResultSignal(List<MatchingEngine.RecoveryMatchResult> recoveryResults) {
        PersistSignal signal = new PersistSignal();
        signal.setBizId("RECOVER_" + System.currentTimeMillis());
        signal.setSignalType(PersistSignalType.RECOVERY_RESULT);
        signal.setRecoveryResults(recoveryResults);
        return signal;
    }
}