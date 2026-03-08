package com.example.trading.infrastructure.disruptor;
import com.example.trading.infrastructure.disruptor.pool.PersistEventObjectPoolFactory;
import com.example.trading.domain.pool.PersistSignalObjectPoolFactory;

import com.example.trading.application.PersistCoreService;
import com.example.trading.application.PersistHelperService;
import com.example.trading.common.enums.PersistSignalType;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.event.PersistSignal;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 数据一致性保证：Order的撮合是ID分片的单线程，保证Disruptor的持久化请求再无锁队列中一定是顺序的。因此这里trade的批量更新
 * 的相关代码只需要用Map存储insert，遇到重复订单一定是update，直接更新insertMap中的数据，这样就等于把insert+update-->insert。
 */
@Slf4j
@Component
public class PersistEventHandler implements EventHandler<PersistEvent> {

    private final PersistCoreService persistCoreService;
    private final PersistHelperService persistHelperService;
    private final PersistSignalObjectPoolFactory signalPoolFactory;
    private final PersistEventObjectPoolFactory eventPoolFactory;

    // 批量队列+线程池（保留原有逻辑）
    private final Queue<PersistSignal> batchQueue = new LinkedList<>();
    private static final int BATCH_SIZE = 100;
    private static final long BATCH_TIMEOUT_MS = 100; // 100ms超时
    private volatile long lastProcessTime = System.currentTimeMillis();
    private final ThreadPoolTaskExecutor dbExecutor;
    private final ThreadPoolTaskExecutor retryExecutor;

    @Autowired
    public PersistEventHandler(PersistCoreService persistCoreService, PersistHelperService persistHelperService,
                               @Qualifier("dbPersistenceExecutor") ThreadPoolTaskExecutor dbPersistenceExecutor,
                               @Qualifier("retryExecutor") ThreadPoolTaskExecutor retryExecutor,
                               PersistSignalObjectPoolFactory signalPoolFactory,
                               PersistEventObjectPoolFactory eventPoolFactory) {
        this.persistCoreService = persistCoreService;
        this.persistHelperService = persistHelperService;
        this.dbExecutor = dbPersistenceExecutor;
        this.retryExecutor = retryExecutor;
        this.signalPoolFactory = signalPoolFactory;
        this.eventPoolFactory = eventPoolFactory;
    }

    @Override
    public void onEvent(PersistEvent event, long sequence, boolean endOfBatch) {
        PersistSignal signal = event.getSignal();
        if (signal == null) {
            eventPoolFactory.returnEvent(event);
            return;
        }

        try {
            batchQueue.offer(signal);
            long now = System.currentTimeMillis();

            // ✅ 优化：降低endOfBatch的优先级，优先攒够BATCH_SIZE或超时
            boolean shouldProcess = false;
            if (batchQueue.size() >= BATCH_SIZE) {
                shouldProcess = true;
            } else if (now - lastProcessTime >= BATCH_TIMEOUT_MS && !batchQueue.isEmpty()) {
                shouldProcess = true;
            } else if (endOfBatch && !batchQueue.isEmpty() && batchQueue.size() >= BATCH_SIZE / 2) {
                // ✅ 只有endOfBatch且队列大小>=BATCH_SIZE/2时才触发
                shouldProcess = true;
            }

            if (shouldProcess) {
                processBatchPersist();
                lastProcessTime = now;
            }
        } finally {
            event.setSignal(null);
            eventPoolFactory.returnEvent(event);
        }
    }

    private void processBatchPersist() {
        Queue<PersistSignal> tempQueue = new LinkedList<>(batchQueue);
        batchQueue.clear();

        dbExecutor.submit(() -> {
            // ========== 1. 拆分数据容器（核心优化：明确区分insert/update） ==========
            Map<String, PersistSignal> orderSignalMap = new LinkedHashMap<>();
            Map<String, PersistSignal> cancelSignalMap = new LinkedHashMap<>();
            Map<String, PersistSignal> recoverySignalMap = new LinkedHashMap<>();

            // 核心拆分：insertOrders（新订单，matchedOrder）、updateOrders（待更新订单，counterOrders）
            // 使用LinkedHashMap保证顺序，且后入队的同ID订单覆盖先入队的，保证最终状态
            Map<String, Order> insertOrderMap = new LinkedHashMap<>(); // 需INSERT的订单（matchedOrder）
            Map<String, Order> updateOrderMap = new LinkedHashMap<>(); // 需UPDATE的订单（counterOrders）
            List<Trade> batchAllTrades = new ArrayList<>();
            Map<String, String> batchOrderProcessingKeys = new HashMap<>();
            List<Order> batchCancelOrders = new ArrayList<>();
            Map<String, String> batchCancelProcessingKeys = new HashMap<>();
            List<MatchingEngine.RecoveryMatchResult> batchRecoveryResults = new ArrayList<>();

            for (PersistSignal signal : tempQueue) {
                try {
                    switch (signal.getSignalType()) {
                        case ORDER_AND_TRADES: // 如果A有两笔交易过来，一定是insert在前，update在后。
                            orderSignalMap.put(signal.getBizId(), signal);

                            // 1. 处理需INSERT的matchedOrder（新订单）
                            if (signal.getMatchedOrder() != null) {
                                Order matchedOrder = signal.getMatchedOrder();
                                String matchedOrderId = matchedOrder.getClOrderId();
                                // 后入队的同ID订单覆盖先入队的，保证最终是最新状态
                                insertOrderMap.put(matchedOrderId, matchedOrder);
                                batchOrderProcessingKeys.put(matchedOrderId, signal.getProcessingKey());
                            }

                            // 2. 处理需UPDATE的counterOrders（成交对手方订单）
                            if (signal.getCounterOrders() != null && !signal.getCounterOrders().isEmpty()) {
                                for (Order counterOrder : signal.getCounterOrders()) {
                                    String counterOrderId = counterOrder.getClOrderId();
                                    // 仅保留需要更新的字段：status、qty、version（过滤其他字段，避免冗余更新）
                                    Order existingUpdateOrder = updateOrderMap.get(counterOrderId);
                                    if (existingUpdateOrder != null) {
                                        // 若已有该订单的更新记录，仅覆盖最新的状态、数量、版本
                                        existingUpdateOrder.setStatus(counterOrder.getStatus());
                                        existingUpdateOrder.setQty(counterOrder.getQty());
                                        existingUpdateOrder.setVersion(counterOrder.getVersion());
                                    } else {
                                        // 首次添加，仅保留核心更新字段（其他字段无需传递）
                                        Order updateOrder = new Order();
                                        updateOrder.setClOrderId(counterOrderId);
                                        updateOrder.setStatus(counterOrder.getStatus());
                                        updateOrder.setQty(counterOrder.getQty());
                                        updateOrder.setVersion(counterOrder.getVersion());
                                        updateOrderMap.put(counterOrderId, updateOrder);
                                    }
                                }
                            }

                            // 3. 处理交易记录（直接追加，无冲突）
                            if (signal.getTrades() != null && !signal.getTrades().isEmpty()) {
                                batchAllTrades.addAll(signal.getTrades());
                            }
                            break;

                        case CANCEL_ORDER:
                            cancelSignalMap.put(signal.getBizId(), signal);
                            if (signal.getCanceledOrder() != null) {
                                batchCancelOrders.add(signal.getCanceledOrder());
                                batchCancelProcessingKeys.put(signal.getCanceledOrder().getClOrderId(), signal.getProcessingKey());
                            }
                            break;

                        case RECOVERY_RESULT:
                            recoverySignalMap.put(signal.getBizId(), signal);
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

            // ========== 2. 拆分批量操作（insert/update独立处理） ==========
            // 2.1 处理INSERT订单（matchedOrder）
            boolean insertBatchSuccess = true;
            try {
                List<Order> insertOrders = new ArrayList<>(insertOrderMap.values());
                if (!insertOrders.isEmpty()) {
                    persistCoreService.batchInsertOrders(insertOrders); // 专门的INSERT方法
                    log.info("批量INSERT订单{}条完成", insertOrders.size());
                }
            } catch (Exception e) {
                log.error("订单INSERT批量持久化失败，准备精准重试", e);
                insertBatchSuccess = false;
                persistHelperService.incrementPersistFailCounter();
            }

            // 2.2 处理UPDATE订单（counterOrders，仅更新status/qty/version）
            boolean updateBatchSuccess = true;
            try {
                List<Order> updateOrders = new ArrayList<>(updateOrderMap.values());
                if (!updateOrders.isEmpty()) {
                    persistCoreService.batchUpdateTradedOrder(updateOrders); // 专门的UPDATE方法
                    log.info("批量UPDATE订单{}条完成（仅更新status/qty/version）", updateOrders.size());
                }
            } catch (Exception e) {
                log.error("订单UPDATE批量持久化失败，准备精准重试", e);
                updateBatchSuccess = false;
                persistHelperService.incrementPersistFailCounter();
            }

            // 2.3 处理交易记录（独立INSERT）
            boolean tradeBatchSuccess = true;
            try {
                if (!batchAllTrades.isEmpty()) {
                    persistCoreService.batchInsertTrades(batchAllTrades);
                    log.info("批量INSERT交易记录{}条完成", batchAllTrades.size());
                }
            } catch (Exception e) {
                log.error("交易记录批量持久化失败，准备精准重试", e);
                tradeBatchSuccess = false;
                persistHelperService.incrementPersistFailCounter();
            }

            // 2.4 处理撤单（UPDATE）
            boolean cancelBatchSuccess = true;
            try {
                if (!batchCancelOrders.isEmpty()) {
                    persistCoreService.batchPersistCancelOrders(batchCancelOrders);
                    batchCancelProcessingKeys.forEach((bizId, processingKey) -> {
                        persistHelperService.markProcessingKeyDone(bizId, processingKey);
                    });
                    log.info("批量持久化撤单{}条完成", batchCancelOrders.size());
                }
            } catch (Exception e) {
                log.error("撤单批量持久化失败，准备精准重试", e);
                cancelBatchSuccess = false;
                persistHelperService.incrementPersistFailCounter();
            }

            // 2.5 处理恢复结果
            boolean recoveryBatchSuccess = true;
            try {
                if (!batchRecoveryResults.isEmpty()) {
                    persistCoreService.doPersistRecoveryResults(batchRecoveryResults);
                    log.info("批量持久化恢复结果{}条完成", batchRecoveryResults.size());
                }
            } catch (Exception e) {
                log.error("恢复结果批量持久化失败，准备精准重试", e);
                recoveryBatchSuccess = false;
                persistHelperService.incrementPersistFailCounter();
            }

            // 标记ProcessingKey完成（仅当insert/update/trade都成功时）
            if (insertBatchSuccess && updateBatchSuccess && tradeBatchSuccess) {
                batchOrderProcessingKeys.forEach((bizId, processingKey) -> {
                    persistHelperService.markProcessingKeyDone(bizId, processingKey);
                });
                persistHelperService.incrementPersistSuccessCounter();
            }

            // ========== 3. 精准重试（按类型拆分） ==========
            if (!insertBatchSuccess && !orderSignalMap.isEmpty()) {
                retryFailedSignals(orderSignalMap.values(), retryExecutor, PersistSignalType.ORDER_AND_TRADES.name(), "INSERT");
            }
            if (!updateBatchSuccess && !orderSignalMap.isEmpty()) {
                retryFailedSignals(orderSignalMap.values(), retryExecutor, PersistSignalType.ORDER_AND_TRADES.name(), "UPDATE");
            }
            if (!tradeBatchSuccess && !orderSignalMap.isEmpty()) {
                retryFailedSignals(orderSignalMap.values(), retryExecutor, PersistSignalType.ORDER_AND_TRADES.name(), "TRADE");
            }
            if (!cancelBatchSuccess && !cancelSignalMap.isEmpty()) {
                retryFailedSignals(cancelSignalMap.values(), retryExecutor, PersistSignalType.CANCEL_ORDER.name(), "CANCEL");
            }
            if (!recoveryBatchSuccess && !recoverySignalMap.isEmpty()) {
                retryFailedSignals(recoverySignalMap.values(), retryExecutor, PersistSignalType.RECOVERY_RESULT.name(), "RECOVERY");
            }
            for (PersistSignal signal : tempQueue) {
                signalPoolFactory.returnSignal(signal);
            }
        });
    }

    /**
     * 优化重试逻辑：区分失败类型（INSERT/UPDATE/TRADE）
     */
    private void retryFailedSignals(Collection<PersistSignal> failedSignals, ThreadPoolTaskExecutor retryExecutor,
                                    String type, String failType) {
        int maxConcurrency = 5;
        Semaphore semaphore = new Semaphore(maxConcurrency);

        for (PersistSignal signal : failedSignals) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("获取重试并发许可失败，业务ID：{}，失败类型：{}", signal.getBizId(), failType, e);
                continue;
            }

            persistHelperService.executeWithRetry(
                    () -> {
                        try {
                            // 重试时也区分操作类型，只重试失败的部分
                            if ("INSERT".equals(failType)) {
                                persistCoreService.doPersistOrderInsert(signal.getMatchedOrder());
                            } else if ("UPDATE".equals(failType)) {
                                if (signal.getCounterOrders() != null) {
                                    for (Order counterOrder : signal.getCounterOrders()) {
                                        persistCoreService.doUpdateTradedOrder(counterOrder);
                                    }
                                }
                            } else if ("TRADE".equals(failType)) {
                                persistCoreService.batchInsertTrades(signal.getTrades());
                            } else {
                                handleSinglePersist(signal);
                            }
                        } finally {
                            signalPoolFactory.returnSignal(signal);
                            semaphore.release();
                        }
                    },
                    signal.getBizId(),
                    type + "_" + failType, // 标记失败类型，便于监控
                    retryExecutor
            ).whenComplete((success, ex) -> {
                if (!success || ex != null) {
                    log.error("[持久化] 业务[{}] 失败类型[{}] 所有重试次数耗尽仍失败", signal.getBizId(), failType);
                    persistHelperService.sendAlarm(signal.getBizId(), failType + "精准重试失败：" + (ex != null ? ex.getMessage() : "未知错误"));
                }
            });
        }
    }

    private void handleSinglePersist(PersistSignal signal) {
        try {
            switch (signal.getSignalType()) {
                case ORDER_AND_TRADES:
                    // 重试时也区分insert/update
                    if (signal.getMatchedOrder() != null) {
                        persistCoreService.doPersistOrderInsert(signal.getMatchedOrder());
                    }
                    if (signal.getCounterOrders() != null && !signal.getCounterOrders().isEmpty()) {
                        for (Order counterOrder : signal.getCounterOrders()) {
                            persistCoreService.doUpdateTradedOrder(counterOrder);
                        }
                    }
                    if (signal.getTrades() != null && !signal.getTrades().isEmpty()) {
                        persistCoreService.batchInsertTrades(signal.getTrades());
                    }
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
        } // signalPool后面会返回
    }

    public ThreadPoolTaskExecutor getDbExecutor() {
        return this.dbExecutor;
    }
}