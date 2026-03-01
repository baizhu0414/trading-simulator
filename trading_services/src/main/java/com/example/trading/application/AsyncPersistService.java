package com.example.trading.application;

import com.example.trading.domain.engine.MatchingEngine.RecoveryMatchResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.util.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPersistService {

    private final PersistCoreService persistCoreService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${db.persist.retry.count:3}")
    private int retryCount;

    public static final String PERSIST_RETRY_QUEUE = "trading:persist:retry:queue"; // redis使用

    // 监控指标
    private Counter persistFailCounter;
    private Counter persistRetryCounter;
    private Counter persistSuccessCounter;

    @PostConstruct
    public void initMetrics() {
        // 初始化带标签的计数器
        persistFailCounter = Counter.builder("trading.persist.fail.total")
                .description("持久化最终失败次数")
                .register(meterRegistry);
        persistRetryCounter = Counter.builder("trading.persist.retry.total")
                .description("持久化重试次数总和")
                .register(meterRegistry);
        persistSuccessCounter = Counter.builder("trading.persist.success.total")
                .description("持久化成功次数")
                .register(meterRegistry);
    }

    @Async("dbPersistenceExecutor")
    public void persistOrderAndTrades(Order matchedOrder, List<Order> counterOrders, List<Trade> trades) {
        String bizId = matchedOrder.getClOrderId();
        executePersist( // Runnable在同线程
                () -> persistCoreService.doPersistOrderAndTrades(matchedOrder, counterOrders, trades),
                bizId, "order_and_trades", matchedOrder, counterOrders, trades
        );
    }

    @Async("dbPersistenceExecutor")
    public void persistRecoveryResults(List<RecoveryMatchResult> allRecoveryResults) {
        String batchId = "RECOVER_" + System.currentTimeMillis();
        executePersist(
                () -> persistCoreService.doPersistRecoveryResults(allRecoveryResults),
                batchId, "recovery", allRecoveryResults
        );
    }

    @Async("dbPersistenceExecutor")
    public void persistCancel(Order canceledOrder) {
        String bizId = canceledOrder.getClOrderId();
        executePersist(
                () -> persistCoreService.doPersistCancel(canceledOrder),
                bizId, "cancel", canceledOrder
        );
    }

    /**
     * 持久化执行：包含重试、入队和告警逻辑
     */
    private void executePersist(Runnable task, String bizId, String type, Object... params) {
        if (executeWithRetry(task, bizId, type)) {
            // 成功：记录成功指标
            persistSuccessCounter.increment();
            return;
        }

        // 失败：入队并记录失败指标
        addToRetryQueue(bizId, type, params);
        persistFailCounter.increment();
        sendAlarm(bizId, type + " 持久化失败，已加入重试队列");
    }

    /**
     * 带指数退避的重试逻辑
     */
    private boolean executeWithRetry(Runnable task, String bizId, String type) {
        int attempt = 0;
        while (attempt < retryCount) {
            try {
                task.run();
                return true;
            } catch (Exception e) {
                attempt++;
                persistRetryCounter.increment();
                log.warn("[持久化] 业务[{}] 第{}次重试失败", bizId, attempt, e);
                sleep(100L * (1 << attempt));
            }
        }
        return false;
    }

    /**
     * 将失败任务加入Redis重试队列
     */
    private void addToRetryQueue(String bizId, String type, Object... params) {
        try {
            PersistRetryTask task = PersistRetryTask.builder()
                    .bizId(bizId)
                    .type(type)
                    .params(params)
                    .createTime(LocalDateTime.now())
                    .retryCount(0)
                    .build();
            redisTemplate.opsForList().rightPush(PERSIST_RETRY_QUEUE, JsonUtils.toJson(task));
        } catch (Exception e) {
            log.error("[持久化] 业务[{}] 加入重试队列失败", bizId, e);
            sendAlarm(bizId, "加入重试队列失败，需人工处理");
        }
    }

    /**
     * 发送告警
     */
    public void sendAlarm(String bizId, String msg) {
        log.error("[告警] 业务[{}]：{}", bizId, msg);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Data
    @Builder
    public static class PersistRetryTask {
        private String bizId;
        private String type;
        private Object[] params;
        private LocalDateTime createTime;
        private int retryCount;
    }
}