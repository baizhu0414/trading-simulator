package com.example.trading.application;

import com.example.trading.common.RedisConstant;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 持久化辅助服务（抽离公共方法，打破循环依赖）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistHelperService {
    private final StringRedisTemplate redisTemplate;
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

    public void markProcessingKeyDone(String bizId, String processingKey) {
        if (processingKey == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(processingKey, RedisConstant.STATUS_DONE,
                    RedisConstant.DONE_KEY_TTL_HOURS, TimeUnit.HOURS);
            log.info("[持久化] 订单[{}]标记为完成(DONE)", bizId);
        } catch (Exception e) {
            log.warn("[持久化] 成功但标记Redis Key失败: {}", processingKey, e);
        }
    }

    public boolean executeWithRetry(Runnable task, String bizId, String type) {
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