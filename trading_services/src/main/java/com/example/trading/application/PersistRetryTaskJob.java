package com.example.trading.application;

import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.util.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated // 用AsyncPersistService替代
public class PersistRetryTaskJob {

    private final StringRedisTemplate redisTemplate;
    private final AsyncPersistService asyncPersistService;
    private final MeterRegistry meterRegistry;

    private static final int MAX_RETRY_COUNT = 3;
    private static final int BATCH_PROCESS_LIMIT = 1000;

    // 监控指标
    private Counter jobProcessCounter;
    private Counter jobDiscardCounter;
    private Counter jobErrorCounter;

    @PostConstruct
    public void initMetrics() {
        jobProcessCounter = Counter.builder("trading.persist.job.process.total")
                .description("重试任务执行次数")
                .register(meterRegistry);
        jobDiscardCounter = Counter.builder("trading.persist.job.discard.total")
                .description("超过最大重试次数而丢弃的任务数")
                .register(meterRegistry);
        jobErrorCounter = Counter.builder("trading.persist.job.error.total")
                .description("重试任务处理异常次数")
                .register(meterRegistry);
    }

    /**
     * 定时消费Redis重试队列
     * 每分钟执行一次，每次最多处理1000条任务避免阻塞
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void consumeRetryQueue() {
        int processedCount = 0;

        try {
            while (processedCount < BATCH_PROCESS_LIMIT) {
                String taskJson = redisTemplate.opsForList().leftPop(AsyncPersistService.PERSIST_RETRY_QUEUE);

                if (taskJson == null) {
                    break;
                }

                processSingleTask(taskJson);
                processedCount++;
            }

            if (processedCount > 0) {
                log.info("本次重试队列消费完成，共处理 {} 条任务", processedCount);
            }
        } catch (RedisConnectionFailureException e) {
            log.error("Redis连接异常，重试队列消费中断", e);
            jobErrorCounter.increment();
            asyncPersistService.sendAlarm("REDIS_ERROR", "Redis连接失败，重试队列消费中断");
        } catch (Exception e) {
            log.error("重试队列消费发生未知异常", e);
            jobErrorCounter.increment();
        }
    }

    /**
     * 处理单条重试任务
     */
    private void processSingleTask(String taskJson) {
        try {
            AsyncPersistService.PersistRetryTask task = JsonUtils.fromJson(taskJson, AsyncPersistService.PersistRetryTask.class);

            if (task == null) {
                log.warn("非法的任务格式，已丢弃：{}", taskJson);
                return;
            }

            if (task.getRetryCount() >= MAX_RETRY_COUNT) {
                log.error("任务超过最大重试次数，丢弃并告警：bizId={}", task.getBizId());
                jobDiscardCounter.increment();
                asyncPersistService.sendAlarm(task.getBizId(),
                        "重试队列任务超过" + MAX_RETRY_COUNT + "次，需人工处理");
                return;
            }

            executeRetryTask(task);
            jobProcessCounter.increment();

        } catch (Exception e) {
            log.error("处理重试任务失败，尝试重新入队：{}", taskJson, e);
            jobErrorCounter.increment();
            recoverTask(taskJson);
        }
    }

    /**
     * 执行具体的业务重试逻辑
     */
    private void executeRetryTask(AsyncPersistService.PersistRetryTask task) {
        String bizId = task.getBizId();
        log.info("开始执行重试任务：bizId={}, type={}, 当前重试次数={}",
                bizId, task.getType(), task.getRetryCount());

        switch (task.getType()) {
            case "order_and_trades":
                Order matchedOrder = (Order) task.getParams()[0];
                List<Order> counterOrders = (List<Order>) task.getParams()[1];
                List<Trade> trades = (List<Trade>) task.getParams()[2];
                asyncPersistService.persistOrderAndTrades(matchedOrder, counterOrders, trades);
                break;
            case "cancel":
                Order canceledOrder = (Order) task.getParams()[0];
                asyncPersistService.persistCancel(canceledOrder);
                break;
            case "recovery":
                List<MatchingEngine.RecoveryMatchResult> recoveryResults =
                        (List<MatchingEngine.RecoveryMatchResult>) task.getParams()[0];
                asyncPersistService.persistRecoveryResults(recoveryResults);
                break;
            default:
                throw new RuntimeException("未知的重试任务类型：" + task.getType());
        }

        log.info("重试任务执行成功：bizId={}", bizId);
    }

    /**
     * 任务执行失败时的回收入队逻辑
     */
    private void recoverTask(String taskJson) {
        try {
            // 重新解析以增加重试次数
            AsyncPersistService.PersistRetryTask task = JsonUtils.fromJson(taskJson, AsyncPersistService.PersistRetryTask.class);
            if (task != null) {
                task.setRetryCount(task.getRetryCount() + 1);
                redisTemplate.opsForList().rightPush(AsyncPersistService.PERSIST_RETRY_QUEUE, JsonUtils.toJson(task));
            }
        } catch (RedisConnectionFailureException ex) {
            log.error("Redis不可用，任务回收入队失败，数据可能丢失：{}", taskJson, ex);
            asyncPersistService.sendAlarm("REDIS_ERROR", "任务回收入队失败，需人工介入");
        }
    }
}