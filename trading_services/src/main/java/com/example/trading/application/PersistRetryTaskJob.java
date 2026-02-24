package com.example.trading.application;

import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import com.example.trading.util.JsonUtils;
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
public class PersistRetryTaskJob {
    private final StringRedisTemplate redisTemplate;
    private final AsyncPersistService asyncPersistService;
    private static final int MAX_RETRY_COUNT = 5; // 兜底重试次数

    /**
     * 每分钟消费一次重试队列（修复无限循环+空值+日志问题）
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void consumeRetryQueue() {
//        log.info("开始消费持久化重试队列"); // 日志移到循环外，仅打印一次
        try {
            while (true) {
                // 1. 从队列头部取出任务
                String taskJson = redisTemplate.opsForList().leftPop(AsyncPersistService.PERSIST_RETRY_QUEUE);

                // 2. 核心退出条件：队列为空，终止循环（解决无限循环问题）
                if (taskJson == null) {
                    log.info("重试队列为空，结束本次消费");
                    break;
                }

                try {
                    // 3. 解析任务（仅当taskJson非空时执行，避免空指针）
                    AsyncPersistService.PersistRetryTask task = JsonUtils.fromJson(taskJson, AsyncPersistService.PersistRetryTask.class);
                    if (task == null) {
                        log.warn("重试任务解析为null，跳过该任务：{}", taskJson);
                        continue;
                    }

                    // 4. 超过最大重试次数，告警并跳过
                    if (task.getRetryCount() >= MAX_RETRY_COUNT) {
                        asyncPersistService.sendAlarm(task.getClOrderId(),
                                "重试队列任务超过最大重试次数（" + MAX_RETRY_COUNT + "次），需人工处理");
                        continue;
                    }

                    // 5. 执行重试任务
                    executeRetryTask(task);

                } catch (Exception e) {
                    log.error("消费重试队列任务失败，任务内容：{}", taskJson, e);
                    // 6. 仅当taskJson非空时，才重新入队（避免NPE）
                    try {
                        redisTemplate.opsForList().rightPush(AsyncPersistService.PERSIST_RETRY_QUEUE, taskJson);
                    } catch (RedisConnectionFailureException ex) {
                        log.error("Redis连接失败，无法将任务重新入队，任务内容：{}", taskJson, ex);
                        asyncPersistService.sendAlarm("REDIS_ERROR", "Redis故障导致任务无法入队，内容：" + taskJson);
                    }
                }
            }
        } catch (RedisConnectionFailureException e) {
            // 7. 捕获Redis全局异常，避免定时任务崩溃
            log.error("Redis连接失败，消费重试队列任务中断", e);
            asyncPersistService.sendAlarm("REDIS_ERROR", "Redis连接失败，重试队列消费中断：" + e.getMessage());
        } catch (Exception e) {
            log.error("消费重试队列任务全局异常", e);
        }

//        log.info("消费持久化重试队列完成"); // 日志移到循环外，仅打印一次
    }

    /**
     * 执行重试任务（补充Redis异常捕获）
     */
    private void executeRetryTask(AsyncPersistService.PersistRetryTask task) {
        String clOrderId = task.getClOrderId();
        try {
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
                    List<MatchingEngine.RecoveryMatchResult> recoveryResults = (List<MatchingEngine.RecoveryMatchResult>) task.getParams()[0];
                    asyncPersistService.persistRecoveryResults(recoveryResults);
                    break;
                default:
                    String errorMsg = "未知的重试任务类型：" + task.getType();
                    log.error(errorMsg);
                    throw new RuntimeException(errorMsg);
            }
            log.info("重试队列任务执行成功：订单[{}]", clOrderId);
        } catch (Exception e) {
            log.error("重试队列任务执行失败：订单[{}]", clOrderId, e);
            // 重试失败，次数+1后重新入队（补充Redis异常捕获）
            try {
                task.setRetryCount(task.getRetryCount() + 1);
                String newTaskJson = JsonUtils.toJson(task);
                redisTemplate.opsForList().rightPush(AsyncPersistService.PERSIST_RETRY_QUEUE, newTaskJson);
            } catch (RedisConnectionFailureException ex) {
                log.error("Redis连接失败，无法将任务重新入队：订单[{}]", clOrderId, ex);
                asyncPersistService.sendAlarm(clOrderId, "任务执行失败且Redis不可用，需人工处理：" + e.getMessage());
            }
        }
    }
}