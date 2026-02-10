package com.example.trading.domain.engine;

import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 撮合引擎
 * 核心逻辑：
 * 1. 买订单（BUY）优先匹配卖队列的最低价格；
 * 2. 卖订单（SELL）优先匹配买队列的最高价格；
 * 3. 支持部分成交，剩余订单继续挂单；
 * 4. 全程线程安全，基于OrderBook的线程安全容器实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {
    private final OrderBook orderBook;
    private final PriceGenerator priceGenerator;

    /**
     * 执行撮合逻辑（价格优先+时间优先）
     * @param newOrder 新提交的订单
     * @return 撮合后的订单（包含成交状态/剩余数量）
     */
    public Order match(Order newOrder) {
        if (newOrder == null || newOrder.getQty() <= 0) {
            log.error("新订单非法，无法撮合：{}", newOrder);
            newOrder.setStatus(OrderStatusEnum.REJECTED);
            return newOrder;
        }

        String securityId = newOrder.getSecurityId();
        SideEnum newOrderSide = newOrder.getSide();
        int remainingQty = newOrder.getQty(); // 剩余未成交数量
        newOrder.setStatus(OrderStatusEnum.MATCHING);

        try {
            // 1. 获取对手方的价格有序Map（买找卖，卖找买）
            SideEnum counterSide = newOrderSide == SideEnum.BUY ? SideEnum.SELL : SideEnum.BUY;
            ConcurrentSkipListMap<Double, Queue<Order>> counterPriceMap = orderBook.getPriceMap(securityId, counterSide);

            // 2. 遍历对手方最优价格，逐笔撮合（直到剩余数量为0或无匹配价格）
            for (Double counterPrice : counterPriceMap.keySet()) {
                // 终止条件：剩余数量为0 或 当前价格不满足撮合条件
                if (remainingQty <= 0 || !isPriceMatch(newOrderSide, newOrder.getPrice(), counterPrice)) {
                    break;
                }

                // 3. 获取当前价格下的对手方订单队列
                Queue<Order> counterOrderQueue = counterPriceMap.get(counterPrice);
                if (counterOrderQueue == null || counterOrderQueue.isEmpty()) {
                    continue;
                }

                // 4. 逐笔匹配队列中的订单（时间优先）
                while (remainingQty > 0 && !counterOrderQueue.isEmpty()) {
                    Order counterOrder = counterOrderQueue.peek(); // 取队首订单（不移除）
                    if (counterOrder == null) {
                        break;
                    }

                    // 5. 计算成交数量（取新订单剩余量 和 对手方订单剩余量的最小值）
                    int matchQty = Math.min(remainingQty, counterOrder.getQty());
                    // 6. 生成成交价
                    double matchPrice = priceGenerator.generatePrice(newOrder, counterOrder);

                    // 7. 执行成交逻辑
                    executeMatch(newOrder, counterOrder, matchQty, matchPrice);

                    // 8. 更新剩余数量
                    remainingQty -= matchQty;

                    // 9. 若对手方订单完全成交，从队列移除
                    if (counterOrder.getQty() == 0) {
                        counterOrderQueue.poll(); // 移除队首订单
                        log.info("对手方订单[{}]完全成交，已从队列移除", counterOrder.getClOrderId());
                    }

                    // 10. 若新订单剩余量为0，终止撮合
                    if (remainingQty <= 0) {
                        break;
                    }
                }

                // 11. 若当前价格队列空，移除该价格节点
                if (counterOrderQueue.isEmpty()) {
                    counterPriceMap.remove(counterPrice);
                    log.info("股票[{}]对手方[{}]价格[{}]队列已空，移除该价格节点",
                            securityId, counterSide.getDesc(), counterPrice);
                }
            }

            // 8. 更新新订单状态
            updateNewOrderStatus(newOrder, remainingQty);

            // 9. 若新订单未完全成交，添加到订单簿挂单
            if (remainingQty > 0) {
                newOrder.setQty(remainingQty);
                orderBook.addOrder(newOrder);
                log.info("新订单[{}]部分成交，剩余数量[{}]已挂单", newOrder.getClOrderId(), remainingQty);
            } else {
                log.info("新订单[{}]完全成交，无需挂单", newOrder.getClOrderId());
            }

        } catch (Exception e) {
            log.error("撮合订单[{}]时发生异常", newOrder.getClOrderId(), e);
            newOrder.setStatus(OrderStatusEnum.REJECTED);
        }

        return newOrder;
    }

    /**
     * 判断价格是否满足撮合条件
     * - 买订单：买价 >= 卖价
     * - 卖订单：卖价 <= 买价
     */
    private boolean isPriceMatch(SideEnum newOrderSide, double newOrderPrice, double counterPrice) {
        return newOrderSide == SideEnum.BUY
                ? newOrderPrice >= counterPrice
                : newOrderPrice <= counterPrice;
    }

    /**
     * 执行单笔成交逻辑（更新订单数量+状态，记录成交日志）
     */
    private void executeMatch(Order newOrder, Order counterOrder, int matchQty, double matchPrice) {
        // 更新新订单数量
        newOrder.setQty(newOrder.getQty() - matchQty);
        // 更新对手方订单数量
        counterOrder.setQty(counterOrder.getQty() - matchQty);

        // 日志记录成交信息
        log.info("撮合成交：新订单[{}] vs 对手方订单[{}] | 成交价格[{}] | 成交数量[{}] | " +
                        "新订单剩余[{}] | 对手方剩余[{}]",
                newOrder.getClOrderId(), counterOrder.getClOrderId(),
                matchPrice, matchQty, newOrder.getQty(), counterOrder.getQty());
    }

    /**
     * 更新新订单的最终状态
     */
    private void updateNewOrderStatus(Order newOrder, int remainingQty) {
        if (remainingQty <= 0) {
            newOrder.setStatus(OrderStatusEnum.FULL_FILLED); // 完全成交
        } else if (remainingQty < newOrder.getQty()) {
            newOrder.setStatus(OrderStatusEnum.PART_FILLED); // 部分成交
        } else {
            newOrder.setStatus(OrderStatusEnum.MATCHING); // 未成交，挂单中
        }
    }
}