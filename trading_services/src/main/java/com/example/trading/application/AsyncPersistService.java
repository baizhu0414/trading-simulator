package com.example.trading.application;

import com.example.trading.common.RedisConstant;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 持久化任务入口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPersistService {

    private final PersistCoreService persistCoreService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    @Value("${db.persist.retry.count:3}")
    private int retryCount;
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
    public void persistOrderAndTrades(Order matchedOrder, List<Order> counterOrders, List<Trade> trades, String processingKey) {
        String bizId = matchedOrder.getClOrderId();
        executePersist(
                () -> persistCoreService.doPersistOrderAndTrades(matchedOrder, counterOrders, trades),
                bizId, "order_and_trades",
                processingKey, // 传入 Redis Key
                matchedOrder, counterOrders, trades
        );
    }

    @Async("dbPersistenceExecutor")
    public void persistRecoveryResults(List<RecoveryMatchResult> allRecoveryResults) {
        String batchId = "RECOVER_" + System.currentTimeMillis();
        executePersist(
                () -> persistCoreService.doPersistRecoveryResults(allRecoveryResults),
                batchId, "recovery",
                null,
                allRecoveryResults
        );
    }

    @Async("dbPersistenceExecutor")
    public void persistCancel(Order canceledOrder, String processingKey) {
        String bizId = canceledOrder.getClOrderId();
        executePersist(
                () -> persistCoreService.doPersistCancel(canceledOrder),
                bizId, "cancel",
                processingKey, // 传递 key
                canceledOrder
        );
    }

    /**
     * 持久化执行：包含重试、入队和告警逻辑
     */
    private void executePersist(Runnable task, String bizId, String type, Object... params) {
        String processingKey = (String) params[0];
        if (executeWithRetry(task, bizId, type)) {
            try {
                redisTemplate.opsForValue().set(processingKey, RedisConstant.STATUS_DONE,
                        RedisConstant.DONE_KEY_TTL_HOURS, TimeUnit.HOURS);
                log.info("[持久化] 订单[{}]标记为完成(DONE)", bizId);
            } catch (Exception e) {
                log.warn("[持久化] 成功但删除Redis Key失败: {}", processingKey, e);
            }
            // 成功：记录成功指标
            persistSuccessCounter.increment();
            return;
        }

        if (processingKey != null) {
            try {
                redisTemplate.expire(processingKey, RedisConstant.PROCESSING_KEY_TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("[持久化] 延长Redis TTL失败", e);
            }
        }
        // 失败：入队并记录失败指标
        Object[] taskParams = Arrays.copyOfRange(params, 1, params.length);
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
}