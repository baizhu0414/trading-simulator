package com.example.trading.infrastructure.disruptor;

import com.example.trading.application.PersistCoreService;
import com.example.trading.application.PersistHelperService; // 替换原AsyncPersistService
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.event.PersistSignal;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class PersistEventHandler implements EventHandler<PersistEvent> {
    // 1. 依赖抽离后的PersistHelperService（无循环）
    private final PersistCoreService persistCoreService;
    private final PersistHelperService persistHelperService; // 替换原AsyncPersistService

    // 批量队列+线程池（保留原有逻辑）
    private final Queue<PersistSignal> batchQueue = new LinkedList<>();
    private static final int BATCH_SIZE = 100;
    private final ExecutorService dbExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // 2. 构造器注入（无循环依赖）
    @Autowired // 或直接写构造器，推荐构造器注入
    public PersistEventHandler(PersistCoreService persistCoreService, PersistHelperService persistHelperService) {
        this.persistCoreService = persistCoreService;
        this.persistHelperService = persistHelperService; // 不再依赖AsyncPersistService
    }

    // 3. 原有onEvent逻辑保留，仅替换asyncPersistService为persistHelperService
    @Override
    public void onEvent(PersistEvent event, long sequence, boolean endOfBatch) throws Exception {
        PersistSignal signal = event.getSignal();
        if (signal == null) {
            return;
        }

        batchQueue.offer(signal);
        if (batchQueue.size() >= BATCH_SIZE || endOfBatch) {
            log.debug("触发批量处理：队列大小={}，是否批次末尾={}", batchQueue.size(), endOfBatch);
            processBatchPersist();
        }
        signal.setProcessed(true);
    }

    // 4. processBatchPersist逻辑保留，仅替换asyncPersistService为persistHelperService
    private void processBatchPersist() {
        Queue<PersistSignal> tempQueue = new LinkedList<>(batchQueue);
        batchQueue.clear();

        dbExecutor.submit(() -> {
            List<Order> batchAllOrders = new ArrayList<>();
            List<Trade> batchAllTrades = new ArrayList<>();
            Map<String, String> batchOrderProcessingKeys = new HashMap<>();
            List<Order> batchCancelOrders = new ArrayList<>();
            Map<String, String> batchCancelProcessingKeys = new HashMap<>();
            List<MatchingEngine.RecoveryMatchResult> batchRecoveryResults = new ArrayList<>();

            for (PersistSignal signal : tempQueue) {
                try {
                    switch (signal.getSignalType()) {
                        case ORDER_AND_TRADES:
                            if (signal.getMatchedOrder() != null) {
                                batchAllOrders.add(signal.getMatchedOrder());
                                batchOrderProcessingKeys.put(signal.getMatchedOrder().getClOrderId(), signal.getProcessingKey());
                            }
                            if (signal.getCounterOrders() != null && !signal.getCounterOrders().isEmpty()) {
                                batchAllOrders.addAll(signal.getCounterOrders());
                            }
                            if (signal.getTrades() != null && !signal.getTrades().isEmpty()) {
                                batchAllTrades.addAll(signal.getTrades());
                            }
                            break;
                        case CANCEL_ORDER:
                            if (signal.getCanceledOrder() != null) {
                                batchCancelOrders.add(signal.getCanceledOrder());
                                batchCancelProcessingKeys.put(signal.getCanceledOrder().getClOrderId(), signal.getProcessingKey());
                            }
                            break;
                        case RECOVERY_RESULT:
                            if (signal.getRecoveryResults() != null && !signal.getRecoveryResults().isEmpty()) {
                                batchRecoveryResults.addAll(signal.getRecoveryResults());
                            }
                            break;
                        default:
                            log.error("未知的持久化信号类型：{}", signal.getSignalType());
                    }
                } catch (Exception e) {
                    log.error("合并批量数据失败，业务ID：{}", signal.getBizId(), e);
                }
            }

            try {
                if (!batchAllOrders.isEmpty() || !batchAllTrades.isEmpty()) {
                    persistCoreService.batchPersistOrderAndTrades(batchAllOrders, batchAllTrades);
                    batchOrderProcessingKeys.forEach((bizId, processingKey) -> {
                        // 替换为persistHelperService
                        persistHelperService.markProcessingKeyDone(bizId, processingKey);
                    });
                    log.info("批量持久化订单{}条、交易记录{}条完成", batchAllOrders.size(), batchAllTrades.size());
                }

                if (!batchCancelOrders.isEmpty()) {
                    persistCoreService.batchPersistCancelOrders(batchCancelOrders);
                    batchCancelProcessingKeys.forEach((bizId, processingKey) -> {
                        // 替换为persistHelperService
                        persistHelperService.markProcessingKeyDone(bizId, processingKey);
                    });
                    log.info("批量持久化撤单{}条完成", batchCancelOrders.size());
                }

                if (!batchRecoveryResults.isEmpty()) {
                    persistCoreService.doPersistRecoveryResults(batchRecoveryResults);
                    log.info("批量持久化恢复结果{}条完成", batchRecoveryResults.size());
                }

                // 替换为persistHelperService
                persistHelperService.incrementPersistSuccessCounter();
            } catch (Exception e) {
                log.error("批量持久化整体失败，开始单条重试", e);
                // 替换为persistHelperService
                persistHelperService.incrementPersistFailCounter();

                for (PersistSignal signal : tempQueue) {
                    // 替换为persistHelperService
                    persistHelperService.executeWithRetry(() -> {
                        handleSinglePersist(signal);
                    }, signal.getBizId(), signal.getSignalType().name());
                    // 替换为persistHelperService
                    persistHelperService.sendAlarm(signal.getBizId(), "批量失败，单条重试：" + e.getMessage());
                }
            }
        });
    }

    // 5. handleSinglePersist逻辑保留，替换asyncPersistService为persistHelperService
    private void handleSinglePersist(PersistSignal signal) {
        try {
            switch (signal.getSignalType()) {
                case ORDER_AND_TRADES:
                    persistCoreService.doPersistOrderAndTrades(
                            signal.getMatchedOrder(),
                            signal.getCounterOrders(),
                            signal.getTrades()
                    );
                    persistHelperService.markProcessingKeyDone(signal.getBizId(), signal.getProcessingKey());
                    break;
                case CANCEL_ORDER:
                    persistCoreService.doPersistCancel(signal.getCanceledOrder());
                    persistHelperService.markProcessingKeyDone(signal.getBizId(), signal.getProcessingKey());
                    break;
                case RECOVERY_RESULT:
                    persistCoreService.doPersistRecoveryResults(signal.getRecoveryResults());
                    break;
                default:
                    log.error("未知的持久化信号类型：{}", signal.getSignalType());
            }
        } catch (Exception e) {
            log.error("单条信号重试失败，业务ID：{}", signal.getBizId(), e);
            throw e;
        }
    }
}