package com.example.trading.infrastructure.persistence;

import com.example.trading.application.AsyncPersistService;
import com.example.trading.application.OrderIdempotentService;
import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.config.ShardingMatchingExecutor;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.OrderBook;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.domain.validation.OrderValidator;
import com.example.trading.mapper.OrderMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 应用崩溃订单恢复服务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRecoveryService implements CommandLineRunner {
    private final OrderMapper orderMapper;
    private final OrderIdempotentService orderIdempotentService;
    private final OrderBook orderBook;
    private final MatchingEngine matchingEngine;
    private final SelfTradeChecker selfTradeChecker;
    private final OrderValidator orderValidator;
    private final ShardingMatchingExecutor shardingMatchingExecutor;

    @Value("${trading.recovery.enable:true}")
    private boolean recoveryEnable;

    // 默认恢复状态加入NOT_FILLED
    @Value("${trading.recovery.recover-status:MATCHING,NOT_FILLED,PART_FILLED}")
    private String recoverStatus;

    @Value("${trading.recovery.batch-size:100}")
    private int batchSize;

    private final AsyncPersistService asyncPersistenceService;

    private volatile boolean recoveryCompleted = false; // 防止一致性检测服务在应用刚启动时报错

    public boolean isRecoveryCompleted() {
        return recoveryCompleted;
    }
    private final MeterRegistry meterRegistry;
    /* 订单恢复任务执行失败次数计数器 */
    private Counter recoveryTaskFailedCounter;
    /* 订单恢复成功数量计数器 */
    private Counter recoveryOrderSuccessCounter;
    /* 订单恢复失败数量计数器 */
    private Counter recoveryOrderFailCounter;
    /* 恢复后主动撮合成交数量计数器 */
    private Counter recoveryTradeTotalCounter;

    @PostConstruct
    public void initMetrics() {
        recoveryTaskFailedCounter = meterRegistry.counter("trading.recovery.task.failed");
        recoveryOrderSuccessCounter = meterRegistry.counter("trading.recovery.order.success");
        recoveryOrderFailCounter = meterRegistry.counter("trading.recovery.order.fail");
        recoveryTradeTotalCounter = meterRegistry.counter("trading.recovery.trade.total");
    }

    @Override
    public void run(String... args) {
        log.info("【启动】应用启动，开始执行崩溃恢复流程...");

        // 1. 预热布隆过滤器
        try {
            log.info("【启动】开始预热布隆过滤器...");
            List<String> allIds = orderMapper.selectAllClOrderIds();
            orderIdempotentService.bulkLoad(allIds);
            log.info("【启动】布隆过滤器预热完成，加载 ID 数量：{}", allIds.size());
        } catch (Exception e) {
            log.error("【启动】布隆过滤器预热失败，但不影响服务启动", e);
        }

        if (!recoveryEnable) {
            log.info("【订单恢复】功能已关闭，跳过");
            recoveryCompleted = true;
            return;
        }

        // 用于统计的变量（放在主线程栈，不需要线程安全）
        final AtomicInteger totalRecovered = new AtomicInteger(0);
        int totalRecoverSkipped = 0;
        final AtomicInteger totalRecoverFailed = new AtomicInteger(0);
        Set<String> allRecoveredSecurities = new HashSet<>();

        try {
            List<OrderStatusEnum> statusList = parseRecoverStatus(recoverStatus);
            if (statusList.isEmpty()) {
                log.warn("【订单恢复】无有效恢复状态，跳过");
                recoveryCompleted = true;
                return;
            }
            log.info("【订单恢复】需要恢复的状态：{}", statusList.stream().map(OrderStatusEnum::getDesc).collect(Collectors.joining(",")));

            int pageNum = 1;
            PageInfo<Order> orderPageInfo;
            int totalPages = Integer.MAX_VALUE;

            do {
                PageHelper.startPage(pageNum, batchSize);
                List<Order> batchOrders = orderMapper.selectByStatusIn(statusList);
                orderPageInfo = new PageInfo<>(batchOrders);

                if (pageNum == 1) {
                    totalPages = orderPageInfo.getPages();
                    if (totalPages == 0) break;
                }

                if (batchOrders.isEmpty()) {
                    pageNum++;
                    continue;
                }

                // 1. 先在主线程做过滤和校验（CPU 密集型，不涉及共享内存）
                List<Order> validOrdersToRecover = new ArrayList<>();
                for (Order order : batchOrders) {
                    if (order.getStatus().isFinalStatus()) {
                        log.debug("【订单恢复】订单[{}]已是终态，跳过", order.getClOrderId());
                        totalRecoverSkipped++;
                        continue;
                    }
                    List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
                    if (!validateErrors.isEmpty()) {
                        log.warn("【订单恢复】订单[{}]校验失败，跳过", order.getClOrderId());
                        totalRecoverSkipped++;
                        continue;
                    }
                    // 修正状态
                    OrderStatusEnum originalStatus = order.getStatus();
                    if (originalStatus == OrderStatusEnum.PROCESSING || originalStatus == OrderStatusEnum.MATCHING) {
                        order.setStatus(OrderStatusEnum.NOT_FILLED);
                    }
                    validOrdersToRecover.add(order);
                    allRecoveredSecurities.add(order.getSecurityId());
                }

                if (validOrdersToRecover.isEmpty()) {
                    pageNum++;
                    continue;
                }

                // 2. 使用 CountDownLatch 等待所有分片加载任务完成
                // 理由：CountDownLatch 可以精准控制，任务一完成就唤醒主线程，无需盲目 sleep
                CountDownLatch loadLatch = new CountDownLatch(validOrdersToRecover.size());

                log.info("【订单恢复】本批次需恢复 {} 笔订单，开始并行加载...", validOrdersToRecover.size());

                for (Order order : validOrdersToRecover) {
                    final Order orderToLoad = order; // lambda 需要 final 变量

                    // 提交给对应股票的分片线程执行
                    shardingMatchingExecutor.submitAsync(order.getSecurityId(), () -> {
                        try {
                            log.debug("【订单恢复】线程[{}]正在加载订单[{}]", Thread.currentThread().getName(), orderToLoad.getClOrderId());
                            loadOrderToMemory(orderToLoad);
                            totalRecovered.incrementAndGet();
                        } catch (Exception e) {
                            log.error("【订单恢复】订单[{}]加载异常", orderToLoad.getClOrderId(), e);
                            totalRecoverFailed.incrementAndGet();
                        } finally {
                            // 无论成功失败，计数器减一，防止死锁
                            loadLatch.countDown();
                        }
                    });
                }

                // 3. 主线程等待，直到所有订单加载完毕
                // 理由：await() 会释放 CPU 资源，进入 WAITING 状态，
                // 直到最后一个线程执行 countDown()，它会立刻被唤醒，比 sleep(2000) 高效且精准
                log.info("【订单恢复】等待分片线程加载订单簿...");
                loadLatch.await();
                log.info("【订单恢复】本批次订单加载完成");

                pageNum++;
            } while (pageNum <= totalPages);

            log.info("【订单恢复】订单加载阶段完成，成功{}笔，跳过{}笔，失败{}笔", totalRecovered, totalRecoverSkipped, totalRecoverFailed);

            // ========== 核心修改点 2：并行执行主动撮合 ==========

            if (allRecoveredSecurities.isEmpty()) {
                log.info("【订单恢复】没有需要恢复的股票，跳过主动撮合");
                recoveryCompleted = true;
                return;
            }

            // 使用线程安全的 List 来收集多线程撮合结果
            List<MatchingEngine.RecoveryMatchResult> allRecoveryResults = Collections.synchronizedList(new ArrayList<>());

            // 再次使用 CountDownLatch 等待撮合完成
            CountDownLatch matchLatch = new CountDownLatch(allRecoveredSecurities.size());

            log.info("【订单恢复】开始对 {} 支股票进行主动撮合...", allRecoveredSecurities.size());

            for (String securityId : allRecoveredSecurities) {
                final String secId = securityId;

                shardingMatchingExecutor.submitAsync(secId, () -> {
                    try {
                        log.info("【订单恢复】线程[{}]开始主动撮合股票[{}]", Thread.currentThread().getName(), secId);
                        List<MatchingEngine.RecoveryMatchResult> results = matchingEngine.matchOrderBookOrders(secId);
                        if (results != null && !results.isEmpty()) {
                            allRecoveryResults.addAll(results);
                            log.info("【订单恢复】股票[{}]主动撮合产生 {} 笔成交", secId, results.size());
                        }
                    } catch (Exception e) {
                        log.error("【订单恢复】股票[{}]主动撮合失败", secId, e);
                    } finally {
                        matchLatch.countDown();
                    }
                });
            }

            // 等待所有撮合任务完成
            matchLatch.await();
            log.info("【订单恢复】主动撮合阶段完成");

            // 4. 统一持久化
            if (!allRecoveryResults.isEmpty()) {
                log.info("【订单恢复】总计产生 {} 笔成交，提交异步持久化...", allRecoveryResults.size());
                recoveryTradeTotalCounter.increment(allRecoveryResults.size());
                asyncPersistenceService.persistRecoveryResults(allRecoveryResults);
            }

        } catch (Exception e) {
            recoveryTaskFailedCounter.increment();
            log.error("【订单恢复】全局异常", e);
        } finally {
            // 无论成功失败，最后都标记恢复完成，允许健康检查通过
            recoveryCompleted = true;
            log.info("【订单恢复】流程全部结束");
        }
    }

    /**
     * 解析恢复状态
     */
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
                        log.warn("【订单恢复】状态{}无效，跳过", status);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 辅助方法：统一加载内存
     */
    private void loadOrderToMemory(Order order) {
        // 1. 加入订单簿
        orderBook.addOrder(order);
        // 2. 补填风控缓存
        selfTradeChecker.putCache(order);
    }

}