package com.example.trading.infrastructure.persistence;

import com.example.trading.application.ExchangeService;
import com.example.trading.application.response.BaseResponse;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.OrderBook;
import com.example.trading.domain.model.Order;
import com.example.trading.mapper.OrderMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
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
 * 订单崩溃恢复服务（MyBatis版，适配NOT_FILLED状态+优化统计逻辑）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRecoveryService implements CommandLineRunner {
    private final OrderMapper orderMapper;
    private final OrderBook orderBook;
    private final MatchingEngine matchingEngine; // 新增：注入撮合引擎,新增恢复后主动撮合逻辑

    // 注入ExchangeService，用于重新处理MATCHING/PROCESSING订单
    private final ExchangeService exchangeService;

    @Value("${trading.recovery.enable:true}")
    private boolean recoveryEnable;

    // 核心修改1：默认恢复状态加入NOT_FILLED（未成交订单需恢复）
    @Value("${trading.recovery.recover-status:NEW,PROCESSING,MATCHING,NOT_FILLED,PART_FILLED}")
    private String recoverStatus;

    @Value("${trading.recovery.batch-size:100}")
    private int batchSize;

    @Resource
    private PlatformTransactionManager transactionManager;
    // 核心修改2：显式指定事务传播级别，增强可读性
    private final TransactionDefinition transactionDefinition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);

    @Override
    public void run(String... args) {
        if (!recoveryEnable) {
            log.info("【订单恢复】功能已关闭");
            return;
        }

        int totalRecovered = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

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
                List<Order> batchOrders = orderMapper.selectByStatusInAndCreateTimeAfter(statusList, expireTime);
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

                pageNum++;
            } while (pageNum <= totalPages);

            log.info("【订单恢复】完成，总计待恢复{}笔，成功{}笔，跳过{}笔，失败{}笔",
                    totalRecovered + totalSkipped + totalFailed,
                    totalRecovered, totalSkipped, totalFailed);

        } catch (Exception e) {
            log.error("【订单恢复】全局异常", e);
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
     * 数据恢复,并对部分订单进行撮合流程。
     */
    public BatchResult processBatchOrders(List<Order> batchOrders) {
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (Order order : batchOrders) {
            TransactionStatus txStatus = transactionManager.getTransaction(transactionDefinition);
            try {
                // 1. 超时订单直接跳过（双重校验，避免Mapper过滤失效）
                if (order.getCreateTime().isBefore(LocalDateTime.now().minusHours(24))) {
                    log.info("【订单恢复】订单[{}]已超时（>24小时），跳过", order.getClOrderId());
                    skipCount++;
                    transactionManager.commit(txStatus);
                    continue;
                }

                // 2. 终态订单处理
                if (order.getStatus().isFinalStatus()) {
                    if (order.getStatus() == OrderStatusEnum.NOT_FILLED) {
                        // 未成交订单也属于终态：直接加入OrderBook
                        orderBook.addOrder(order);
                        log.info("【订单恢复】订单[{}]（未成交）重新加入订单簿", order.getClOrderId());
                        successCount++;
                    } else {
                        // 其他终态（REJECTED/FULL_FILLED等）：跳过
                        log.info("【订单恢复】订单[{}]已是终态（{}），跳过", order.getClOrderId(), order.getStatus().getDesc());
                        skipCount++;
                    }
                    transactionManager.commit(txStatus);
                    continue;
                }

                // 3. 非终态订单处理（PART_FILLED逻辑）
                if (order.getStatus() == OrderStatusEnum.MATCHING || order.getStatus() == OrderStatusEnum.PART_FILLED) {
                    // 撮合中：加入OrderBook + 重置为NOT_FILLED（终态，避免状态残留）
                    order.setStatus(OrderStatusEnum.NOT_FILLED);
                    int updateCount = orderMapper.updateById(order);
                    if (updateCount > 0) {
                        order.setVersion(order.getVersion()+1);
                        orderBook.addOrder(order);
                        log.info("【订单恢复】订单[{}]（撮合中）重置为未成交并加入订单簿", order.getClOrderId());
                        successCount++;
                    }
                } else if (order.getStatus() == OrderStatusEnum.PROCESSING) {
                    // PROCESSING：重新走完整流程（校验/风控/撮合）
                    // 调用ExchangeService重新撮合处理，内部会调用 matchingEngine.match
                    BaseResponse result = exchangeService.recoverOrder(order);
                    log.info("【订单恢复】订单[{}]（{}）重新处理完成，结果：{}",
                            order.getClOrderId(), order.getStatus().getDesc(), result);
                    successCount++;
                }

                transactionManager.commit(txStatus);

            } catch (Exception e) {
                transactionManager.rollback(txStatus);
                log.error("【订单恢复】订单[{}]失败", order.getClOrderId(), e);
                failCount++;
            }
        }

        log.info("【订单恢复】批次处理：成功{}，跳过{}，失败{}", successCount, skipCount, failCount);
        return new BatchResult(successCount, skipCount, failCount);
    }

    /**
     * 核心修改5：扩展BatchResult，拆分skipCount（独立统计）
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