package com.example.trading.application;

import com.example.trading.common.RedisConstant;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.event.PersistSignal;
import com.example.trading.domain.event.PersistSignalFactory;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.infrastructure.disruptor.DisruptorManager;
import com.example.trading.util.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPersistService {
    private final DisruptorManager disruptorManager;
    private final PersistSignalFactory persistSignalFactory;

    public void persistOrderAndTrades(Order matchedOrder, List<Order> counterOrders, List<Trade> trades, String processingKey) {
        PersistSignal signal = persistSignalFactory.createOrderAndTradesSignal(
                matchedOrder, counterOrders, trades, processingKey
        );
        disruptorManager.producePersistSignal(signal);
        log.info("订单[{}]持久化信号已发送到Disruptor队列", matchedOrder.getClOrderId());
    }

    public void persistRecoveryResults(List<MatchingEngine.RecoveryMatchResult> allRecoveryResults) {
        PersistSignal signal = persistSignalFactory.createRecoveryResultSignal(allRecoveryResults);
        disruptorManager.producePersistSignal(signal);
        log.info("恢复结果持久化信号已发送到Disruptor队列，批次大小：{}", allRecoveryResults.size());
    }

    public void persistCancel(Order canceledOrder, String processingKey) {
        PersistSignal signal = persistSignalFactory.createCancelOrderSignal(canceledOrder, processingKey);
        disruptorManager.producePersistSignal(signal);
        log.info("撤单[{}]持久化信号已发送到Disruptor队列", canceledOrder.getClOrderId());
    }

}