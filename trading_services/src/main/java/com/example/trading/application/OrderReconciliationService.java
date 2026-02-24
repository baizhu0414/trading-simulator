package com.example.trading.application;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.OrderBook;
import com.example.trading.domain.model.Order;
import com.example.trading.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 订单数据对账服务（适配新版OrderBook）
 * 核心：保证内存订单簿状态与数据库最终一致
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReconciliationService {
    private final OrderMapper orderMapper;
    private final MatchingEngine matchingEngine;

    /**
     * 定时对账任务（每5分钟执行一次）
     * 注意：启动类需加 @EnableScheduling 开启定时任务
     */
    @Scheduled(fixedRate = 5 * 60 * 1000) // 5分钟 = 300000毫秒
    public void checkDataConsistency() {
        log.info("========== 开始执行订单数据对账任务 ==========");
        try {
            // 1. 从数据库查询所有未完成订单（PROCESSING/MATCHING/NOT_FILLED/PART_FILLED）
            List<Order> dbUnfinishedOrders = orderMapper.selectUnfinishedOrders();
            log.info("数据库中未完成订单数量：{}", dbUnfinishedOrders.size());

            if (dbUnfinishedOrders.isEmpty()) {
                log.info("对账任务：无未完成订单，无需对账");
                log.info("========== 订单数据对账任务执行完成 ==========\n");
                return;
            }

            // 2. 从内存订单簿获取所有订单
            OrderBook orderBook = matchingEngine.getOrderBook();
            List<Order> memoryAllOrders = orderBook.getAllOrders();
            log.info("内存订单簿中订单数量：{}", memoryAllOrders.size());

            // 3. 核心对账逻辑：逐笔对比数据库和内存状态
            int fixCount = 0; // 修复的订单数
            int alarmCount = 0; // 告警的订单数

            for (Order dbOrder : dbUnfinishedOrders) {
                String clOrderId = dbOrder.getClOrderId();
                // 3.1 从内存中查找对应订单
                Optional<Order> memoryOrderOpt = memoryAllOrders.stream()
                        .filter(o -> clOrderId.equals(o.getClOrderId()))
                        .findFirst();

                if (memoryOrderOpt.isEmpty()) {
                    // 异常：数据库有订单但内存无 → 触发告警（需人工核对）
                    alarmCount++;
                    log.error("对账异常【{}】：订单[{}]数据库存在但内存订单簿中无",
                            alarmCount, clOrderId);
                    sendAlarm(clOrderId, "数据库存在未完成订单但内存订单簿中无，可能是内存数据丢失");
                    continue;
                }

                // 3.2 取出内存订单，对比核心字段
                Order memoryOrder = memoryOrderOpt.get();
                boolean isMismatch = false;
                StringBuilder mismatchMsg = new StringBuilder();

                // 对比订单状态
                if (!memoryOrder.getStatus().equals(dbOrder.getStatus())) {
                    isMismatch = true;
                    mismatchMsg.append(String.format("状态不匹配（库：%s → 内存：%s）；",
                            dbOrder.getStatus().getDesc(), memoryOrder.getStatus().getDesc()));
                }

                // ========== 替换原有“已成交数量/剩余数量”对比逻辑 ==========
// 1. 对比剩余数量（Order中的qty字段就是剩余数量）
                if (memoryOrder.getQty() != null && dbOrder.getQty() != null) {
                    if (!memoryOrder.getQty().equals(dbOrder.getQty())) {
                        isMismatch = true;
                        mismatchMsg.append(String.format("剩余数量不匹配（库：%d → 内存：%d）；",
                                dbOrder.getQty(), memoryOrder.getQty()));
                    }
                } else if (memoryOrder.getQty() != null || dbOrder.getQty() != null) {
                    isMismatch = true;
                    mismatchMsg.append("剩余数量一方为空；");
                }

                // 2. 计算并对比已成交数量（已成交 = 原始数量 - 剩余数量）
                Integer memoryExecutedQty = calculateExecutedQty(memoryOrder);
                Integer dbExecutedQty = calculateExecutedQty(dbOrder);

                if (memoryExecutedQty != null && dbExecutedQty != null) {
                    if (!memoryExecutedQty.equals(dbExecutedQty)) {
                        isMismatch = true;
                        mismatchMsg.append(String.format("已成交数量不匹配（库：%d → 内存：%d）；",
                                dbExecutedQty, memoryExecutedQty));
                    }
                } else if (memoryExecutedQty != null || dbExecutedQty != null) {
                    isMismatch = true;
                    mismatchMsg.append("已成交数量计算异常（原始数量/剩余数量缺失）；");
                }

                // 3.3 状态不一致 → 以内存为准修复数据库（核心原则：用户感知的内存状态是最终态）
                if (isMismatch) {
                    fixCount++;
                    int updateCount = orderMapper.updateById(memoryOrder);
                    log.info("对账修复【{}】：订单[{}]，不匹配项：{}，数据库更新行数：{}",
                            fixCount, clOrderId, mismatchMsg.toString(), updateCount);
                }
            }

            // 4. 对账结果汇总
            log.info("========== 对账任务汇总 ==========");
            log.info("核对未完成订单总数：{}", dbUnfinishedOrders.size());
            log.info("修复不一致订单数：{}", fixCount);
            log.info("触发告警订单数：{}", alarmCount);
            log.info("========== 订单数据对账任务执行完成 ==========\n");

        } catch (Exception e) {
            log.error("========== 订单对账任务执行失败 ==========", e);
            sendAlarm("对账任务全局异常", "定时对账任务执行失败：" + e.getMessage());
        }
    }

    /**
     * 工具方法：计算已成交数量（已成交 = 原始数量 - 剩余数量）
     * @param order 订单对象
     * @return 已成交数量（若原始数量/剩余数量为空则返回null）
     */
    private Integer calculateExecutedQty(Order order) {
        if (order == null) {
            return null;
        }
        Integer originalQty = order.getOriginalQty();
        Integer leavesQty = order.getQty(); // Order中的qty是剩余数量

        // 原始数量/剩余数量任一为空，无法计算
        if (originalQty == null || leavesQty == null) {
            log.warn("订单[{}]无法计算已成交数量：原始数量[{}]，剩余数量[{}]",
                    order.getClOrderId(), originalQty, leavesQty);
            return null;
        }

        // 已成交数量不能为负数（防御性校验）
        int executedQty = originalQty - leavesQty;
        if (executedQty < 0) {
            log.error("订单[{}]已成交数量为负：原始数量[{}] - 剩余数量[{}] = [{}]",
                    order.getClOrderId(), originalQty, leavesQty, executedQty);
            return 0; // 兜底返回0，避免异常
        }
        return executedQty;
    }

    /**
     * 发送告警（示例：可对接钉钉/邮件/短信）
     * 实际项目中替换为真实告警逻辑
     */
    private void sendAlarm(String clOrderId, String msg) {
        log.error("[对账告警] 订单[{}]：{}", clOrderId, msg);
        // 示例钉钉告警（需引入钉钉SDK）：
        // dingTalkAlarmService.sendTextMessage("交易系统对账异常", String.format("订单[%s]：%s", clOrderId, msg));
    }
}