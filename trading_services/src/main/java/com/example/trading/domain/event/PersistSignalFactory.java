package com.example.trading.domain.event;

import com.example.trading.common.enums.PersistSignalType;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.pool.PersistSignalObjectPoolFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 持久化信号工厂（统一创建信号，解耦创建逻辑，适配对象池）
 */
@Component
public class PersistSignalFactory {

    private final PersistSignalObjectPoolFactory signalPoolFactory;

    // 注入对象池工厂
    public PersistSignalFactory(PersistSignalObjectPoolFactory signalPoolFactory) {
        this.signalPoolFactory = signalPoolFactory;
    }

    /**
     * 创建「订单+交易」持久化信号（对应原有persistOrderAndTrades）
     */
    public PersistSignal createOrderAndTradesSignal(
            Order matchedOrder, List<Order> counterOrders, List<Trade> trades,
            String processingKey) {
        // 从对象池获取信号，替代 new PersistSignal()
        PersistSignal signal = signalPoolFactory.borrowSignal();
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
        PersistSignal signal = signalPoolFactory.borrowSignal();
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
        PersistSignal signal = signalPoolFactory.borrowSignal();
        signal.setBizId("RECOVER_" + System.currentTimeMillis());
        signal.setSignalType(PersistSignalType.RECOVERY_RESULT);
        signal.setRecoveryResults(recoveryResults);
        return signal;
    }
}