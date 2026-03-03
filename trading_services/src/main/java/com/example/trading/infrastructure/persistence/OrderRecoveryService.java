package com.example.trading.infrastructure.persistence;

import com.example.trading.application.AsyncPersistService;
import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
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
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单崩溃恢复服务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRecoveryService implements CommandLineRunner {
    private final OrderMapper orderMapper;
    private final OrderBook orderBook;
    private final MatchingEngine matchingEngine;
    private final SelfTradeChecker selfTradeChecker;
    private final OrderValidator orderValidator;

    @Value("${trading.recovery.enable:true}")
    private boolean recoveryEnable;

    // 默认恢复状态加入NOT_FILLED
    @Value("${trading.recovery.recover-status:PROCESSING,MATCHING,NOT_FILLED,PART_FILLED}")
    private String recoverStatus;

    @Value("${trading.recovery.batch-size:100}")
    private int batchSize;

    private final AsyncPersistService asyncPersistenceService;

    private volatile boolean recoveryCompleted = false; // 防止一致性检测服务在应用刚启动时报错

    public boolean isRecoveryCompleted() {
        return recoveryCompleted;
    }

    @Resource
    private PlatformTransactionManager transactionManager;
    // 显式指定事务传播级别，增强可读性
    private final TransactionDefinition transactionDefinition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);

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
        if (!recoveryEnable) {
            log.info("【订单恢复】功能已关闭");
            return;
        }

        int totalRecovered = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        Set<String> allRecoveredSecurities = new HashSet<>();

        try {
            // 解析恢复状态
            List<OrderStatusEnum> statusList = parseRecoverStatus(recoverStatus);
            if (statusList.isEmpty()) {
                log.warn("【订单恢复】无有效恢复状态，跳过");
                return;
            }
            log.info("【订单恢复】需要恢复的状态：{}",
                    statusList.stream().map(OrderStatusEnum::getDesc).collect(Collectors.joining(",")));

            // 24小时超时过滤
            LocalDateTime expireTime = LocalDateTime.now().minusHours(24);
            log.info("【订单恢复】过滤24小时前的订单，过期时间：{}", expireTime);

            // 分页恢复
            int pageNum = 1;
            PageInfo<Order> orderPageInfo;
            int totalPages = Integer.MAX_VALUE;

            do {
                PageHelper.startPage(pageNum, batchSize);
                // 按订单状态查找，结合超时过滤功能
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

                // 批量处理
                BatchResult batchResult = processBatchOrders(batchOrders);
                totalRecovered += batchResult.getSuccessCount();
                totalSkipped += batchResult.getSkipCount();
                totalFailed += batchResult.getFailCount();

                recoveryOrderSuccessCounter.increment(batchResult.getSuccessCount());
                recoveryOrderFailCounter.increment(batchResult.getFailCount());

                // 收集本批次的股票代码
                batchOrders.forEach(order -> allRecoveredSecurities.add(order.getSecurityId()));

                pageNum++;
            } while (pageNum <= totalPages);

            log.info("【订单恢复】完成，总计待恢复{}笔，成功{}笔，跳过{}笔，失败{}笔",
                    totalRecovered + totalSkipped + totalFailed,
                    totalRecovered, totalSkipped, totalFailed);

            // 收集所有股票的撮合结果
            List<MatchingEngine.RecoveryMatchResult> allRecoveryResults = new ArrayList<>();

            for (String securityId : allRecoveredSecurities) {
                try {
                    log.info("【订单恢复】开始主动撮合股票[{}]", securityId);
                    List<MatchingEngine.RecoveryMatchResult> results = matchingEngine.matchOrderBookOrders(securityId);
                    allRecoveryResults.addAll(results);
                } catch (Exception e) {
                    log.error("【订单恢复】股票[{}]主动撮合失败", securityId, e);
                }
            }

            // 将所有收集到的撮合结果，一次性丢给异步线程去持久化
            if (!allRecoveryResults.isEmpty()) {
                log.info("【订单恢复】内存撮合完成，产生{}笔成交，提交异步持久化...", allRecoveryResults.size());
                recoveryTradeTotalCounter.increment(allRecoveryResults.size());
                asyncPersistenceService.persistRecoveryResults(allRecoveryResults);
            }

        } catch (Exception e) {
            recoveryTaskFailedCounter.increment();
            log.error("【订单恢复】全局异常", e);
        }
        recoveryCompleted = true; // 用于延迟队长服务，防止应用重启后立刻报错。
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
     * 数据恢复-》状态变更后此处记得修改，当前删除NEW已修改
     */
    public BatchResult processBatchOrders(List<Order> batchOrders) {
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (Order order : batchOrders) {
            TransactionStatus txStatus = transactionManager.getTransaction(transactionDefinition);
            try {
                // 1. 超时订单直接跳过——此逻辑已从数据库OrderMapper中删除，长期运行可以考虑添加。
//                if (order.getCreateTime().isBefore(LocalDateTime.now().minusHours(24))) {
//                    log.info("【订单恢复】订单[{}]已超时（>24小时），跳过", order.getClOrderId());
//                    skipCount++;
//                    transactionManager.commit(txStatus);
//                    continue;
//                }

                // 2. 明确的终态：直接跳过 -》FULL_FILLED, RISK_REJECT, REJECTED, CANCELED
                if (order.getStatus().isFinalStatus()) {
                    log.info("【订单恢复】订单[{}]已是终态（{}），跳过", order.getClOrderId(), order.getStatus().getDesc());
                    skipCount++;
                    transactionManager.commit(txStatus);
                    continue;
                }

                // 3. 剩余状态：全部尝试恢复 -》PROCESSING, MATCHING, NOT_FILLED, PART_FILLED
                List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
                if (!validateErrors.isEmpty()) {
                    log.warn("【订单恢复】订单[{}]校验失败，视为无效跳过", order.getClOrderId());
                    skipCount++;
                    transactionManager.commit(txStatus);
                    continue;
                }

                OrderStatusEnum originalStatus = order.getStatus();
                if (originalStatus == OrderStatusEnum.PROCESSING || originalStatus == OrderStatusEnum.MATCHING) {
                    order.setStatus(OrderStatusEnum.NOT_FILLED);
                }

                // 加载进内存
                loadOrderToMemory(order);

                log.info("【订单恢复】订单[{}]恢复成功（原状态：{} -> 现状态：{}）",
                        order.getClOrderId(), originalStatus.getDesc(), order.getStatus().getDesc());
                successCount++;

                transactionManager.commit(txStatus);

            } catch (Exception e) {
                transactionManager.rollback(txStatus);
                log.error("【订单恢复】订单[{}]恢复异常", order.getClOrderId(), e);
                failCount++;
            }
        }

        log.info("【订单恢复】批次处理：成功{}，跳过{}，失败{}", successCount, skipCount, failCount);
        return new BatchResult(successCount, skipCount, failCount);
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

    /**
     * 扩展BatchResult，拆分skipCount
     */
    private static class BatchResult {
        private final int successCount;
        private final int skipCount;
        private final int failCount;

        public BatchResult(int successCount, int skipCount, int failCount) {
            this.successCount = successCount;
            this.skipCount = skipCount;
            this.failCount = failCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getSkipCount() {
            return skipCount;
        }

        public int getFailCount() {
            return failCount;
        }
    }
}