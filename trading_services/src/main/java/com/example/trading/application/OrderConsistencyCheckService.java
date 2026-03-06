package com.example.trading.application;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.OrderBook;
import com.example.trading.domain.model.Order;
import com.example.trading.infrastructure.persistence.OrderRecoveryService;
import com.example.trading.mapper.OrderMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单数据一致性检查服务
 * 核心：定期校验内存订单簿（OrderBook）与数据库中未完成订单的数据一致性。
 * 职责：发现不一致 -> 记录详细日志 -> 上报监控指标 -> 触发告警。
 * 注：在开发过程中能辅助发现问题，如：
 *  INFO  com.example.trading.domain.engine.OrderBook - 订单簿中总计查询到0笔订单（对账用）
 *  INFO  c.e.trading.application.OrderReconciliationService - 内存订单簿中订单数量：0
 *  ERROR c.e.trading.application.OrderReconciliationService - 对账异常【1】：订单[SEL0000000000003]数据库存在但内存订单簿中无
 *  ERROR c.e.trading.application.OrderReconciliationService - [对账告警] 订单[SEL0000000000003]：数据库存在未完成订单但内存订单簿中无，可能是内存数据丢失
 *  INFO  c.e.trading.application.OrderReconciliationService - ========== 对账任务汇总 ==========
 *  ......
 *  INFO  c.e.trading.application.OrderReconciliationService - 触发告警订单数：1
 *  INFO  c.e.trading.application.OrderReconciliationService - ========== 订单数据对账任务执行完成 ==========
 *  ......
 *  INFO  com.example.trading.domain.engine.OrderBook - 订单[SEL0000000000003]已加入[卖出]方向订单簿，股票[600030]，价格[20.00]，队列长度[1]
 *  INFO  c.e.t.i.persistence.OrderRecoveryService - 【订单恢复】订单[SEL0000000000003]（原状态：部分成交）重置为未成交并加入内存
 *  INFO  c.e.t.i.persistence.OrderRecoveryService - 【订单恢复】开始主动撮合股票[600030]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderConsistencyCheckService {

    private final OrderMapper orderMapper;
    private final MatchingEngine matchingEngine;
    private final OrderRecoveryService orderRecoveryService;
    @Value("${trading.recovery.recover-status:PROCESSING,MATCHING,NOT_FILLED,PART_FILLED}")
    private String recoverStatus;
    private final MeterRegistry meterRegistry;

    private Counter checkTotalCounter;      // 检查任务执行总次数
    private Counter checkFailedCounter;     // 检查任务执行失败次数
    private Counter checkMismatchCounter;   // 发现数据不匹配的订单总数

    @PostConstruct
    public void initMetrics() {
        checkTotalCounter = meterRegistry.counter("trading.consistency.check.total");
        checkFailedCounter = meterRegistry.counter("trading.consistency.check.failed");
        checkMismatchCounter = meterRegistry.counter("trading.consistency.check.mismatch");
    }

    /**
     * 定时一致性检查任务
     */
    @Scheduled(fixedRate = 60 * 60 * 1000) // 60分钟
    public void executeConsistencyCheck() {
        checkTotalCounter.increment();

        // 1. 等待恢复服务完成
        long startTime = System.currentTimeMillis();
        while (!orderRecoveryService.isRecoveryCompleted() && System.currentTimeMillis() - startTime < 10000) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log.warn("等待订单恢复完成被中断", e);
                break;
            }
        }

        log.info("========== 开始执行订单数据一致性检查 ==========");
        try {
            List<OrderStatusEnum> statusList = parseRecoverStatus(recoverStatus);
            if (statusList.isEmpty()) {
                log.info("检查任务：无有效恢复状态，检查通过");
                return;
            }
            // 2. 从数据库查询所有未完成订单
            List<Order> dbUnfinishedOrders = orderMapper.selectByStatusIn(statusList);
            log.info("数据库中未完成订单数量：{}", dbUnfinishedOrders.size());

            if (dbUnfinishedOrders.isEmpty()) {
                log.info("检查任务：无未完成订单，检查通过");
                log.info("========== 订单数据一致性检查完成 ==========\n");
                return;
            }

            // 3. 从内存订单簿获取所有订单
            OrderBook orderBook = matchingEngine.getOrderBook();
            List<Order> memoryAllOrders = orderBook.getAllOrders();
            log.info("内存订单簿中订单数量：{}", memoryAllOrders.size());

            // 4. 核心检查逻辑
            int mismatchCount = 0; // 本次检查发现不一致的订单数

            for (Order dbOrder : dbUnfinishedOrders) {
                String clOrderId = dbOrder.getClOrderId();

                // 检查：内存中是否存在该订单
                Optional<Order> memoryOrderOpt = memoryAllOrders.stream()
                        .filter(o -> clOrderId.equals(o.getClOrderId()))
                        .findFirst();

                if (memoryOrderOpt.isEmpty()) {
                    // 严重异常：数据库有但内存无
                    mismatchCount++;
                    log.error("【严重异常】订单[{}]：数据库存在未完成订单，但内存订单簿中未找到！", clOrderId);
                    sendAlarm(clOrderId, "严重不一致：数据库存在但内存缺失，疑似内存数据丢失");
                    continue;
                }

                // 检查：核心字段是否匹配
                Order memoryOrder = memoryOrderOpt.get();
                boolean isMismatch = false;
                StringBuilder mismatchMsg = new StringBuilder();

                // 对比订单状态
                if (!memoryOrder.getStatus().equals(dbOrder.getStatus())) {
                    isMismatch = true;
                    mismatchMsg.append(String.format("状态不匹配(DB:%s vs Mem:%s); ",
                            dbOrder.getStatus().getDesc(), memoryOrder.getStatus().getDesc()));
                }

                // 对比剩余数量
                if (memoryOrder.getQty() != null && dbOrder.getQty() != null) {
                    if (!memoryOrder.getQty().equals(dbOrder.getQty())) {
                        isMismatch = true;
                        mismatchMsg.append(String.format("剩余数量不匹配(DB:%d vs Mem:%d); ",
                                dbOrder.getQty(), memoryOrder.getQty()));
                    }
                } else if (memoryOrder.getQty() != null || dbOrder.getQty() != null) {
                    isMismatch = true;
                    mismatchMsg.append("剩余数量一方为空; ");
                }

                // 对比已成交数量
                Integer memoryExecutedQty = calculateExecutedQty(memoryOrder);
                Integer dbExecutedQty = calculateExecutedQty(dbOrder);

                if (memoryExecutedQty != null && dbExecutedQty != null) {
                    if (!memoryExecutedQty.equals(dbExecutedQty)) {
                        isMismatch = true;
                        mismatchMsg.append(String.format("已成交数量不匹配(DB:%d vs Mem:%d); ",
                                dbExecutedQty, memoryExecutedQty));
                    }
                } else if (memoryExecutedQty != null || dbExecutedQty != null) {
                    isMismatch = true;
                    mismatchMsg.append("已成交数量计算异常; ");
                }

                // 处理不一致结果
                if (isMismatch) {
                    mismatchCount++;
                    log.warn("【数据不一致】订单[{}]：差异详情 -> {}", clOrderId, mismatchMsg.toString());
                }
            }

            // 5. 检查结果汇总
            if (mismatchCount > 0) {
                checkMismatchCounter.increment(mismatchCount);
            }

            log.info("========== 一致性检查任务汇总 ==========");
            log.info("核对未完成订单总数：{}", dbUnfinishedOrders.size());
            log.info("本次发现不一致订单数：{}", mismatchCount);
            log.info("========== 订单数据一致性检查完成 ==========\n");

        } catch (Exception e) {
            log.error("========== 订单一致性检查任务执行失败 ==========", e);
            sendAlarm("检查任务全局异常", "定时检查任务执行失败：" + e.getMessage());
            checkFailedCounter.increment();
        }
    }

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
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 工具方法：计算已成交数量
     */
    private Integer calculateExecutedQty(Order order) {
        if (order == null) return null;
        Integer originalQty = order.getOriginalQty();
        Integer leavesQty = order.getQty();

        if (originalQty == null || leavesQty == null) {
            log.warn("订单[{}]无法计算已成交数量：原始数量[{}]，剩余数量[{}]",
                    order.getClOrderId(), originalQty, leavesQty);
            return null;
        }

        int executedQty = originalQty - leavesQty;
        if (executedQty < 0) {
            log.error("订单[{}]已成交数量为负：原始数量[{}] - 剩余数量[{}] = [{}]",
                    order.getClOrderId(), originalQty, leavesQty, executedQty);
            return 0;
        }
        return executedQty;
    }

    /**
     * 发送告警
     */
    private void sendAlarm(String clOrderId, String msg) {
        log.error("[一致性告警] 订单[{}]：{}", clOrderId, msg);
    }
}