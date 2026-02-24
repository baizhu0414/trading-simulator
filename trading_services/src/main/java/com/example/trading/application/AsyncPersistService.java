package com.example.trading.application;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import com.example.trading.util.JsonUtils;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 异步持久化服务
 * 职责：仅负责将内存中已确认的状态（订单/成交）写入数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPersistService {
    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;
    private final SelfTradeChecker selfTradeChecker;
    private final StringRedisTemplate redisTemplate;

    @Value("${db.persist.retry.count:3}")
    private int retryCount;

    // 重试队列key
    public static final String PERSIST_RETRY_QUEUE = "trading:persist:retry:queue";

    /**
     * 异步持久化：订单与成交记录（带重试+失败入队）
     */
    @Async("dbPersistenceExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void persistOrderAndTrades(Order matchedOrder, List<Order> counterOrders, List<Trade> trades) {
        String clOrderId = matchedOrder.getClOrderId();
        // 封装持久化逻辑，用于重试
        Runnable persistTask = () -> {
            try {
                log.info("[异步持久化] 开始处理订单[{}]", clOrderId);
                // 1. 批量插入成交记录
                if (!trades.isEmpty()) {
                    tradeMapper.batchInsert(trades);
                    log.info("[异步持久化] 订单[{}]批量插入{}笔成交记录", clOrderId, trades.size());
                }
                // 2. 合并所有待更新订单
                List<Order> allOrders = new ArrayList<>();
                allOrders.add(matchedOrder);
                allOrders.addAll(counterOrders);
                // 3. 批量更新订单状态
                orderMapper.batchUpdateById(allOrders);
                log.info("[异步持久化] 订单[{}]批量更新{}笔订单状态", clOrderId, allOrders.size());
                // 4. 清理风控缓存 + 同步版本号
                for (Order o : allOrders) {
                    if (o.getStatus() == OrderStatusEnum.FULL_FILLED) {
                        selfTradeChecker.removeCache(o.getShareholderId(), o.getSecurityId());
                    }
                    o.setVersion(o.getVersion() + 1);
                }
            } catch (Exception e) {
                throw new RuntimeException("持久化失败", e);
            }
        };

        // 执行持久化 + 重试
        if (!executeWithRetry(persistTask, clOrderId, "order_and_trades", matchedOrder, counterOrders, trades)) {
            // 重试失败，加入延迟队列兜底
            addToRetryQueue(clOrderId, "order_and_trades", matchedOrder, counterOrders, trades);
            // 触发告警
            sendAlarm(clOrderId, "订单持久化重试失败，已加入兜底队列");
        }
    }

    /**
     * 异步持久化：订单崩溃恢复后的撮合结果
     * 逻辑：批量插入成交记录 + 批量更新买卖订单状态 + 清理风控缓存
     */
    @Async("dbPersistenceExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void persistRecoveryResults(List<MatchingEngine.RecoveryMatchResult> allRecoveryResults) {
        if (allRecoveryResults.isEmpty()) {
            log.info("[异步持久化-恢复] 无撮合结果，无需处理");
            return;
        }
        String batchId = "RECOVER_" + System.currentTimeMillis(); // 批次ID，便于日志追踪
        log.info("[异步持久化-恢复] 开始处理{}笔恢复撮合结果，批次ID：{}", allRecoveryResults.size(), batchId);

        // 封装恢复结果持久化逻辑，复用重试框架
        Runnable recoveryPersistTask = () -> {
            try {
                // 1. 收集所有待插入的成交记录、待更新的订单
                List<Trade> allTrades = new ArrayList<>();
                List<Order> allOrders = new ArrayList<>();

                for (MatchingEngine.RecoveryMatchResult result : allRecoveryResults) {
                    Order buyOrder = result.getBuyOrder();
                    Order sellOrder = result.getSellOrder();
                    Trade trade = result.getTrade();

                    // 1.1 收集成交记录（非空才添加）
                    if (trade != null) {
                        allTrades.add(trade);
                    }
                    // 1.2 收集买卖订单（非空才添加）
                    if (buyOrder != null) {
                        allOrders.add(buyOrder);
                    }
                    if (sellOrder != null) {
                        allOrders.add(sellOrder);
                    }
                }

                // 2. 批量插入成交记录（提升性能）
                if (!allTrades.isEmpty()) {
                    tradeMapper.batchInsert(allTrades);
                    log.info("[异步持久化-恢复] 批次{}：批量插入{}笔成交记录", batchId, allTrades.size());
                }

                // 3. 批量更新订单状态（提升性能）
                if (!allOrders.isEmpty()) {
                    orderMapper.batchUpdateById(allOrders);
                    log.info("[异步持久化-恢复] 批次{}：批量更新{}笔订单状态", batchId, allOrders.size());

                    // 4. 清理风控缓存 + 更新订单版本号
                    for (Order order : allOrders) {
                        if (order.getStatus() == OrderStatusEnum.FULL_FILLED) {
                            selfTradeChecker.removeCache(order.getShareholderId(), order.getSecurityId());
                        }
                        order.setVersion(order.getVersion() + 1); // 乐观锁版本号+1
                    }
                }

                log.info("[异步持久化-恢复] 批次{}：处理完成", batchId);
            } catch (Exception e) {
                throw new RuntimeException("恢复撮合结果持久化失败（批次ID：" + batchId + "）", e);
            }
        };

        // 执行重试逻辑：失败则入队+告警
        if (!executeWithRetry(recoveryPersistTask, batchId, "recovery", allRecoveryResults)) {
            addToRetryQueue(batchId, "recovery", allRecoveryResults);
            sendAlarm(batchId, "恢复撮合结果持久化重试失败，已加入兜底队列（批次包含" + allRecoveryResults.size() + "笔结果）");
        }
    }

    /**
     * 异步持久化：撤单记录（补全重试+失败入队逻辑）
     */
    @Async("dbPersistenceExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void persistCancel(Order canceledOrder) {
        String clOrderId = canceledOrder.getClOrderId();
        // 封装撤单持久化逻辑，复用重试框架
        Runnable cancelPersistTask = () -> {
            try {
                log.info("[异步持久化-撤单] 开始处理订单[{}]", clOrderId);

                // 直接更新数据库
                int updateCount = orderMapper.updateById(canceledOrder);
                if (updateCount == 0) {
                    throw new RuntimeException("撤单乐观锁冲突");
                }

                canceledOrder.setVersion(canceledOrder.getVersion() + 1);

                // 清理风控缓存
                selfTradeChecker.removeCache(canceledOrder.getShareholderId(), canceledOrder.getSecurityId());

                log.info("[异步持久化-撤单] 订单[{}]处理完成", clOrderId);
            } catch (Exception e) {
                throw new RuntimeException("撤单持久化失败", e);
            }
        };

        // 执行撤单持久化 + 重试（核心补全逻辑）
        if (!executeWithRetry(cancelPersistTask, clOrderId, "cancel", canceledOrder)) {
            // 重试失败，加入延迟队列兜底
            addToRetryQueue(clOrderId, "cancel", canceledOrder);
            // 触发告警
            sendAlarm(clOrderId, "撤单持久化重试失败，已加入兜底队列");
        }
    }

    /**
     * 通用重试执行逻辑（复用核心）
     */
    private boolean executeWithRetry(Runnable task, String clOrderId, String type, Object... params) {
        int retry = 0;
        while (retry < retryCount) {
            try {
                task.run();
                return true; // 执行成功
            } catch (Exception e) {
                retry++;
                log.error("[异步持久化] 订单[{}]（类型：{}）第{}次重试失败", clOrderId, type, retry, e);
                // 指数退避重试间隔：100ms → 200ms → 400ms...
                try {
                    Thread.sleep(100 * (1 << retry));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    log.warn("[异步持久化] 订单[{}]重试间隔被中断", clOrderId);
                }
            }
        }
        log.error("[异步持久化] 订单[{}]（类型：{}）重试{}次后仍失败", clOrderId, type, retryCount);
        return false; // 重试耗尽仍失败
    }

    /**
     * 通用失败入队逻辑（复用核心）
     */
    private void addToRetryQueue(String clOrderId, String type, Object... params) {
        try {
            PersistRetryTask task = PersistRetryTask.builder()
                    .clOrderId(clOrderId)
                    .type(type)
                    .params(params)
                    .createTime(LocalDateTime.now())
                    .retryCount(0)
                    .build();
            redisTemplate.opsForList().rightPush(PERSIST_RETRY_QUEUE, JsonUtils.toJson(task));
            log.info("[异步持久化] 订单[{}]（类型：{}）已加入重试队列", clOrderId, type);
        } catch (Exception e) {
            log.error("[异步持久化] 订单[{}]（类型：{}）加入重试队列失败", clOrderId, type, e);
            // 队列插入失败，触发紧急告警
            sendAlarm(clOrderId, "订单持久化失败且加入重试队列失败，需人工紧急处理（类型：" + type + "）");
        }
    }

    /**
     * 通用告警逻辑（改为private，符合封装原则）
     */
    void sendAlarm(String clOrderId, String msg) {
        log.error("[告警] 订单[{}]：{}", clOrderId, msg);
        // 实际项目中替换为真实告警逻辑（钉钉/邮件/短信）
        // dingTalkService.sendAlarm("交易系统持久化失败", clOrderId + "：" + msg);
    }

    // 重试任务实体类
    @Data
    @Builder
    public static class PersistRetryTask {
        private String clOrderId;
        private String type; // order_and_trades / cancel / recovery
        private Object[] params;
        private LocalDateTime createTime;
        private int retryCount;
    }
}