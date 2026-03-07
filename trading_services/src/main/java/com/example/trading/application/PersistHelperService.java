package com.example.trading.application;

import com.example.trading.common.RedisConstant;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 持久化辅助服务（抽离公共方法，打破循环依赖）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistHelperService {
    private final StringRedisTemplate redisTemplate;
    // 注入Redis连接工厂，用于状态检查和重启
    private final LettuceConnectionFactory lettuceConnectionFactory;
    private final MeterRegistry meterRegistry;

    @Value("${db.persist.retry.count:3}")
    private int retryCount;

    // 监控指标（和原AsyncPersistService一致）
    private Counter persistFailCounter;
    private Counter persistRetryCounter;
    private Counter persistSuccessCounter;

    @PostConstruct
    public void initMetrics() {
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

    /**
     * 标记Redis Key为完成状态（核心优化：增加连接工厂状态检查+自动重启）
     */
    public void markProcessingKeyDone(String bizId, String processingKey) {
        if (processingKey == null) {
            return;
        }
        try {
            // 关键修复1：检查Redis连接工厂状态，若已停止则尝试重启（非应用关闭场景）
            if (!lettuceConnectionFactory.isRunning()) {
                synchronized (this) { // 加锁避免并发重启
                    if (!lettuceConnectionFactory.isRunning()) {
                        log.warn("[Redis] 连接工厂已停止，尝试重启，业务ID：{}", bizId);
                        lettuceConnectionFactory.start(); // 重启连接工厂
                    }
                }
            }
            // 执行Redis标记操作
            redisTemplate.opsForValue().set(
                    processingKey,
                    RedisConstant.STATUS_DONE,
                    RedisConstant.DONE_KEY_TTL_HOURS,
                    TimeUnit.HOURS
            );
            log.info("[持久化] 订单[{}]标记为完成(DONE)", bizId);
        } catch (IllegalStateException e) {
            // 捕获连接工厂已停止的异常（应用关闭场景），仅打印警告，不影响核心业务
            log.warn("[持久化] 应用正在关闭，Redis连接工厂已停止，跳过标记Key：{}，业务ID：{}", processingKey, bizId);
        } catch (Exception e) {
            // 其他Redis异常（如网络超时），仅警告，不阻断核心流程
            log.warn("[持久化] 成功但标记Redis Key失败: {}", processingKey, e);
        }
    }

    /**
     * 异步执行重试逻辑（在指定的retryExecutor线程池运行）
     * @param task 待重试的任务
     * @param bizId 业务ID
     * @param type 信号类型
     * @param retryExecutor 重试专用线程池
     * @return 异步结果：true=重试成功，false=所有重试都失败
     */
    public CompletableFuture<Boolean> executeWithRetry(Runnable task, String bizId, String type, ThreadPoolTaskExecutor retryExecutor) {
        return CompletableFuture.supplyAsync(() -> {
            int attempt = 0;
            while (attempt < retryCount) {
                try {
                    task.run(); // 执行实际的持久化任务
                    return true;
                } catch (Exception e) {
                    attempt++;
                    persistRetryCounter.increment();
                    log.warn("[持久化] 业务[{}] 第{}次重试失败", bizId, attempt, e);
                    // 退避等待（在retryExecutor的线程中等待，不影响其他线程）
                    try {
                        TimeUnit.MILLISECONDS.sleep(100L * (1 << attempt)); // 改为TimeUnit更优雅
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // 恢复中断标记
                        log.warn("[持久化] 业务[{}] 重试等待被中断", bizId, ie);
                        return false;
                    }
                }
            }
            log.error("[持久化] 业务[{}] 重试{}次后仍失败", bizId, retryCount);
            return false;
        }, retryExecutor); // 指定使用retryExecutor线程池
    }

    public void sendAlarm(String bizId, String msg) {
        log.error("[告警] 业务[{}]：{}", bizId, msg);
    }

    public void incrementPersistFailCounter() {
        persistFailCounter.increment();
    }

    public void incrementPersistSuccessCounter() {
        persistSuccessCounter.increment();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}