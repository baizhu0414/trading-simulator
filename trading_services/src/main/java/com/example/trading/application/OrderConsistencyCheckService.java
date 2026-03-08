package com.example.trading.application;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.infrastructure.disruptor.DisruptorManager;
import com.example.trading.infrastructure.disruptor.PersistEventHandler;
import com.example.trading.infrastructure.persistence.OrderRecoveryService;
import com.example.trading.mapper.OrderMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 订单数据一致性检查服务
 * 修复点：
 * 1. 修正Disruptor RingBuffer消费完成判断逻辑（移除不存在的getNextPublishSequence）
 * 2. 修正LocalDateTime获取毫秒数的方式（替换getTime()）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderConsistencyCheckService {

    private final OrderMapper orderMapper;
    private final MatchingEngine matchingEngine;
    private final OrderRecoveryService orderRecoveryService;
    private final DisruptorManager disruptorManager;
    private final PersistEventHandler persistEventHandler;

    @Value("${trading.recovery.recover-status:PROCESSING,MATCHING,NOT_FILLED,PART_FILLED}")
    private String recoverStatus;
    private final MeterRegistry meterRegistry;

    @Value("${trading.consistency.tolerance-window:30000}")
    private long toleranceWindow;
    @Value("${trading.consistency.max-wait-time:20000}")
    private long maxWaitTime;

    private Counter checkTotalCounter;
    private Counter checkFailedCounter;
    private Counter checkMismatchCounter;
    private Counter checkFalseAlarmCounter;

    private final Map<String, Long> alarmThrottleMap = new ConcurrentHashMap<>();
    private static final long ALARM_THROTTLE_TIME = 5 * 60 * 1000; // 5分钟

    @PostConstruct
    public void initMetrics() {
        checkTotalCounter = meterRegistry.counter("trading.consistency.check.total");
        checkFailedCounter = meterRegistry.counter("trading.consistency.check.failed");
        checkMismatchCounter = meterRegistry.counter("trading.consistency.check.mismatch");
        checkFalseAlarmCounter = meterRegistry.counter("trading.consistency.check.false_alarm");
    }

//    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void executeConsistencyCheck() {
        checkTotalCounter.increment();
        long startTime = System.currentTimeMillis();

        // 等待Disruptor+数据库线程池+恢复服务就绪（修正Disruptor判断逻辑）
        waitForPersistenceReady(startTime);

        log.info("========== 开始执行订单数据一致性检查 ==========");
        try {
            List<OrderStatusEnum> statusList = parseRecoverStatus(recoverStatus);
            if (statusList.isEmpty()) {
                log.info("检查任务：无有效恢复状态，检查通过");
                return;
            }

            List<Order> dbUnfinishedOrders = orderMapper.selectPartByStatusIn(statusList);
            log.info("数据库中未完成订单数量：{}", dbUnfinishedOrders.size());

            if (dbUnfinishedOrders.isEmpty()) {
                log.info("检查任务：无未完成订单，检查通过");
                log.info("========== 订单数据一致性检查完成 ==========\n");
                return;
            }

            // 线程安全读取内存订单簿（依赖OrderBook内部读写锁）
            List<Order> memoryAllOrders = matchingEngine.getOrderBook().getAllOrders();
            log.info("内存订单簿中订单数量：{}", memoryAllOrders.size());

            // 核心检查逻辑（修正LocalDateTime.getTime()）
            int mismatchCount = doConsistencyCheck(dbUnfinishedOrders, memoryAllOrders);

            // 结果汇总
            logSummary(dbUnfinishedOrders.size(), mismatchCount);

        } catch (Exception e) {
            log.error("========== 订单一致性检查任务执行失败 ==========", e);
            sendAlarm("检查任务全局异常", "定时检查任务执行失败：" + e.getMessage());
            checkFailedCounter.increment();
        }
    }

    /**
     * 修正：Disruptor队列消费完成判断逻辑
     */
    private void waitForPersistenceReady(long startTime) {
        while (true) {
            // 正确判断RingBuffer是否无未消费事件：剩余容量 == 初始bufferSize
            boolean isDisruptorEmpty = disruptorManager.isRingBufferEmpty();
            // 数据库线程池是否空闲
            boolean isDbExecutorIdle = isDbPersistenceExecutorIdle();
            // 恢复服务是否完成
            boolean isRecoveryCompleted = orderRecoveryService.isRecoveryCompleted();

            if (isDisruptorEmpty && isDbExecutorIdle && isRecoveryCompleted) {
                log.info("Disruptor队列已排空 + 数据库线程池空闲 + 恢复服务完成，开始检查");
                break;
            }

            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                String warnMsg = String.format(
                        "等待超时（%d秒），强制执行检查！Disruptor空：%s，DB线程池空闲：%s，恢复完成：%s",
                        maxWaitTime / 1000, isDisruptorEmpty, isDbExecutorIdle, isRecoveryCompleted
                );
                log.warn(warnMsg);
                sendAlarm("一致性检查前置等待超时", warnMsg);
                break;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.warn("等待队列排空被中断", e);
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 修正：核心检查逻辑（LocalDateTime转毫秒数）
     */
    private int doConsistencyCheck(List<Order> dbUnfinishedOrders, List<Order> memoryAllOrders) {
        int mismatchCount = 0;
        long currentTime = System.currentTimeMillis();

        for (Order dbOrder : dbUnfinishedOrders) {
            // LocalDateTime获取毫秒数
            long orderCreateTime = dbOrder.getCreateTime().toInstant(ZoneOffset.UTC).toEpochMilli();

            // 窗口期过滤
            if (currentTime - orderCreateTime < toleranceWindow && orderCreateTime != 0) {
                checkFalseAlarmCounter.increment();
                log.debug("订单[{}]创建时间{}，处于{}秒容忍窗口期，跳过检查",
                        dbOrder.getClOrderId(),
                        dbOrder.getCreateTime(),
                        toleranceWindow / 1000);
                continue;
            }

            String clOrderId = dbOrder.getClOrderId();
            Optional<Order> memoryOrderOpt = memoryAllOrders.stream()
                    .filter(o -> clOrderId.equals(o.getClOrderId()))
                    .findFirst();

            if (memoryOrderOpt.isEmpty()) {
                if (isAlarmThrottled(clOrderId)) {
                    log.warn("订单[{}]数据库存在但内存无，已触发过告警（5分钟内），跳过重复告警", clOrderId);
                    continue;
                }
                mismatchCount++;
                String errorMsg = "数据库存在未完成订单，但内存订单簿中未找到！";
                log.error("【严重异常】订单[{}]：{}", clOrderId, errorMsg);
                sendAlarm(clOrderId, "严重不一致：" + errorMsg + " 疑似内存数据丢失");
                continue;
            }

            // 字段一致性检查
            mismatchCount += checkOrderFieldConsistency(dbOrder, memoryOrderOpt.get());
        }

        return mismatchCount;
    }

    private boolean isDbPersistenceExecutorIdle() {
        try {
            ThreadPoolTaskExecutor executor = persistEventHandler.getDbExecutor();
            return executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().size() == 0;
        } catch (Exception e) {
            log.warn("检查数据库线程池状态失败，默认认为非空闲", e);
            return false;
        }
    }

    private int checkOrderFieldConsistency(Order dbOrder, Order memoryOrder) {
        boolean isMismatch = false;
        StringBuilder mismatchMsg = new StringBuilder();

        // 状态对比
        if (!memoryOrder.getStatus().equals(dbOrder.getStatus())) {
            isMismatch = true;
            mismatchMsg.append(String.format("状态不匹配(DB:%s vs Mem:%s); ",
                    dbOrder.getStatus().getDesc(), memoryOrder.getStatus().getDesc()));
        }

        // 剩余数量对比
        if (memoryOrder.getQty() != null && dbOrder.getQty() != null) {
            if (!memoryOrder.getQty().equals(dbOrder.getQty())) {
                isMismatch = true;
                mismatchMsg.append(String.format("剩余数量不匹配(DB:%d vs Mem:%d); ",
                        dbOrder.getQty(), memoryOrder.getQty()));
            }
        } else if (memoryOrder.getQty() != null || dbOrder.getQty() != null) {
            isMismatch = true;
            mismatchMsg.append("剩余数量一方为空; ");
        }

        // 已成交数量对比
        Integer memoryExecutedQty = calculateExecutedQty(memoryOrder);
        Integer dbExecutedQty = calculateExecutedQty(dbOrder);
        if (memoryExecutedQty != null && dbExecutedQty != null) {
            if (!memoryExecutedQty.equals(dbExecutedQty)) {
                isMismatch = true;
                mismatchMsg.append(String.format("已成交数量不匹配(DB:%d vs Mem:%d); ",
                        dbExecutedQty, memoryExecutedQty));
            }
        } else if (memoryExecutedQty != null || dbExecutedQty != null) {
            isMismatch = true;
            mismatchMsg.append("已成交数量计算异常; ");
        }

        if (isMismatch) {
            log.warn("【数据不一致】订单[{}]：差异详情 -> {}", dbOrder.getClOrderId(), mismatchMsg);
            sendAlarm(dbOrder.getClOrderId(), "数据不一致：" + mismatchMsg);
            return 1;
        }
        return 0;
    }

    private void logSummary(int totalCheckCount, int mismatchCount) {
        log.info("========== 一致性检查任务汇总 ==========");
        log.info("核对未完成订单总数：{}", totalCheckCount);
        log.info("本次发现不一致订单数：{}", mismatchCount);
        log.info("========== 订单数据一致性检查完成 ==========\n");

        if (mismatchCount > 0) {
            checkMismatchCounter.increment(mismatchCount);
        }
    }

    private List<OrderStatusEnum> parseRecoverStatus(String recoverStatusStr) {
        if (recoverStatusStr == null || recoverStatusStr.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(recoverStatusStr.split(","))
                .map(String::trim)
                .filter(status -> !status.isBlank())
                .map(status -> {
                    try {
                        return OrderStatusEnum.valueOf(status);
                    } catch (IllegalArgumentException e) {
                        log.warn("无效的恢复状态：{}，已过滤", status);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Integer calculateExecutedQty(Order order) {
        if (order == null) return null;
        Integer originalQty = order.getOriginalQty();
        Integer leavesQty = order.getQty();

        if (originalQty == null || leavesQty == null) {
            log.warn("订单[{}]无法计算已成交数量：原始数量[{}]，剩余数量[{}]",
                    order.getClOrderId(), originalQty, leavesQty);
            return null;
        }

        int executedQty = originalQty - leavesQty;
        if (executedQty < 0) {
            log.error("订单[{}]已成交数量为负：原始数量[{}] - 剩余数量[{}] = [{}]",
                    order.getClOrderId(), originalQty, leavesQty, executedQty);
            return 0;
        }
        return executedQty;
    }

    private boolean isAlarmThrottled(String clOrderId) {
        long lastAlarmTime = alarmThrottleMap.getOrDefault(clOrderId, 0L);
        if (System.currentTimeMillis() - lastAlarmTime < ALARM_THROTTLE_TIME) {
            return true;
        }
        alarmThrottleMap.put(clOrderId, System.currentTimeMillis());
        return false;
    }

    private void sendAlarm(String clOrderId, String msg) {
        log.error("[一致性告警] 订单[{}]：{}", clOrderId, msg);
    }
}