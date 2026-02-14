package com.example.trading.domain.engine;

import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.mapper.OrderMapper;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 撮合引擎（适配BigDecimal价格+移除部分成交+支持零股成交+未成交订单挂单）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {
    private final OrderBook orderBook;
    private final OrderMapper orderMapper; // 新增：用于更新成交订单状态
    private final PriceGenerator priceGenerator;

    /**
     * 执行撮合逻辑（价格优先+时间优先，仅完全成交/不成交）
     * @param newOrder 新提交的订单
     * @return 撮合后的订单（包含成交状态）
     */
    @Timed(value = "trading.order.match.time", description = "订单撮合耗时")
    public Order match(Order newOrder) {
        if (newOrder == null || newOrder.getQty() <= 0) {
            log.error("新订单非法，无法撮合：{}", newOrder);
            newOrder.setStatus(OrderStatusEnum.REJECTED); // 仅非法订单标记为REJECTED
            return newOrder;
        }

        String securityId = newOrder.getSecurityId();
        SideEnum newOrderSide = newOrder.getSide();
        int orderQty = newOrder.getQty();
        newOrder.setStatus(OrderStatusEnum.MATCHING);

        try {
            // 1. 获取对手方的价格有序Map
            SideEnum counterSide = newOrderSide == SideEnum.BUY ? SideEnum.SELL : SideEnum.BUY;
            ConcurrentSkipListMap<BigDecimal, Queue<Order>> counterPriceMap = orderBook.getPriceMap(securityId, counterSide);

            // 2. 标记是否完全成交
            boolean isFullyMatched = false;

            // 3. 遍历对手方最优价格，仅匹配能完全成交的订单
            for (BigDecimal counterPrice : counterPriceMap.keySet()) {
                if (isFullyMatched || !isPriceMatch(newOrderSide, newOrder.getPrice(), counterPrice)) {
                    break;
                }

                Queue<Order> counterOrderQueue = counterPriceMap.get(counterPrice);
                if (counterOrderQueue == null || counterOrderQueue.isEmpty()) {
                    continue;
                }

                // 5. 逐笔匹配队列中的订单（时间优先）
                while (!isFullyMatched && !counterOrderQueue.isEmpty()) {
                    Order counterOrder = counterOrderQueue.peek();
                    if (counterOrder == null) {
                        break;
                    }

                    // 6. 仅当对手单数量 >= 新订单数量时，执行完全成交
                    if (counterOrder.getQty() >= orderQty) {
                        BigDecimal matchPrice = priceGenerator.generatePrice(newOrder, counterOrder);
                        executeFullMatch(newOrder, counterOrder, orderQty, matchPrice);
                        isFullyMatched = true;

                        // 9. 若对手单完全被消耗，从队列移除
                        if (counterOrder.getQty() == 0) {
                            counterOrderQueue.poll();
                            log.info("对手方订单[{}]完全成交，已从队列移除", counterOrder.getClOrderId());
                        }
                        break;
                    } else {
                        log.info("对手方订单[{}]数量[{}]不足，无法完全匹配新订单[{}]数量[{}]，跳过",
                                counterOrder.getClOrderId(), counterOrder.getQty(),
                                newOrder.getClOrderId(), orderQty);
                        counterOrderQueue.poll();
                    }
                }

                // 10. 若当前价格队列空，移除该价格节点
                if (counterOrderQueue.isEmpty()) {
                    counterPriceMap.remove(counterPrice);
                    log.info("股票[{}]对手方[{}]价格[{}]队列已空，移除该价格节点",
                            securityId, counterSide.getDesc(), counterPrice);
                }
            }

            // 11. 更新新订单的最终状态（核心修改：未成交则挂单）
            updateNewOrderStatus(newOrder, isFullyMatched);

            // 12. 日志优化
            if (!isFullyMatched) {
                log.info("新订单[{}]无匹配的对手单，已挂入订单簿（零股数量：{}）", newOrder.getClOrderId(), orderQty);
            } else {
                log.info("新订单[{}]完全成交（零股数量：{}）", newOrder.getClOrderId(), orderQty);
            }

        } catch (Exception e) {
            log.error("撮合订单[{}]时发生异常", newOrder.getClOrderId(), e);
            // 核心修改：异常时区分——非法订单用REJECTED，正常异常用NOT_FILLED
            newOrder.setStatus(newOrder.getQty() <= 0 ? OrderStatusEnum.REJECTED : OrderStatusEnum.NOT_FILLED);
            // 异常未成交也挂单（保证订单不丢失）
            if (newOrder.getStatus() == OrderStatusEnum.NOT_FILLED) {
                orderBook.addOrder(newOrder);
            }
        }

        return newOrder;
    }

    // ========== 新增：主动撮合指定股票的存量订单 ==========
    /**
     * 主动撮合指定股票的存量订单（恢复后触发）
     * @param securityId 股票代码
     */
    @Transactional(rollbackFor = Exception.class)
    public void matchOrderBookOrders(String securityId) {
        log.info("开始主动撮合股票[{}]的存量订单", securityId);

        // 1. 获取该股票的买卖订单簿
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> buyPriceMap = orderBook.getPriceMap(securityId, SideEnum.BUY);
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> sellPriceMap = orderBook.getPriceMap(securityId, SideEnum.SELL);

        if (buyPriceMap.isEmpty() || sellPriceMap.isEmpty()) {
            log.info("股票[{}]订单簿无匹配的买卖订单，跳过主动撮合", securityId);
            return;
        }

        // 2. 遍历买单（价格降序），匹配卖单（价格升序）
        Iterator<Map.Entry<BigDecimal, Queue<Order>>> buyIterator = buyPriceMap.entrySet().iterator();
        while (buyIterator.hasNext()) {
            Map.Entry<BigDecimal, Queue<Order>> buyEntry = buyIterator.next();
            BigDecimal buyPrice = buyEntry.getKey();
            Queue<Order> buyQueue = buyEntry.getValue();

            if (buyQueue.isEmpty()) {
                buyIterator.remove();
                continue;
            }

            // 3. 遍历卖单（价格升序），匹配当前买单价格
            Iterator<Map.Entry<BigDecimal, Queue<Order>>> sellIterator = sellPriceMap.entrySet().iterator();
            while (sellIterator.hasNext()) {
                Map.Entry<BigDecimal, Queue<Order>> sellEntry = sellIterator.next();
                BigDecimal sellPrice = sellEntry.getKey();
                Queue<Order> sellQueue = sellEntry.getValue();

                if (sellQueue.isEmpty()) {
                    sellIterator.remove();
                    continue;
                }

                // 4. 价格匹配校验（买单价格 >= 卖单价格）
                if (buyPrice.compareTo(sellPrice) < 0) {
                    break; // 卖单价格高于买单，后续卖单价格更高，无需继续
                }

                // 5. 逐笔匹配买卖订单
                matchOrderPair(buyQueue, sellQueue, securityId);

                // 6. 若卖单队列空，移除该价格节点
                if (sellQueue.isEmpty()) {
                    sellIterator.remove();
                }
                // 7. 若买单队列空，跳出当前卖单遍历，处理下一个买单
                if (buyQueue.isEmpty()) {
                    break;
                }
            }

            // 8. 若买单队列空，移除该价格节点
            if (buyQueue.isEmpty()) {
                buyIterator.remove();
            }
        }

        log.info("股票[{}]存量订单主动撮合完成", securityId);
    }

    /**
     * 匹配一对买卖订单队列（核心撮合逻辑复用）
     */
    private void matchOrderPair(Queue<Order> buyQueue, Queue<Order> sellQueue, String securityId) {
        while (!buyQueue.isEmpty() && !sellQueue.isEmpty()) {
            Order buyOrder = buyQueue.peek();
            Order sellOrder = sellQueue.peek();

            if (buyOrder == null || sellOrder == null) {
                break;
            }

            // 仅完全成交（保持原有逻辑）
            if (buyOrder.getQty() == sellOrder.getQty()) {
                // 执行成交
                BigDecimal matchPrice = priceGenerator.generatePrice(buyOrder, sellOrder);
                executeFullMatch(buyOrder, sellOrder, buyOrder.getQty(), matchPrice);

                // 移除队列中的订单
                buyQueue.poll();
                sellQueue.poll();

                // 更新数据库状态（终态）
                updateOrderStatus(buyOrder, OrderStatusEnum.FULL_FILLED);
                updateOrderStatus(sellOrder, OrderStatusEnum.FULL_FILLED);

                log.info("主动撮合成交：买单[{}] vs 卖单[{}] | 股票[{}] | 价格[{}] | 数量[{}]",
                        buyOrder.getClOrderId(), sellOrder.getClOrderId(), securityId, matchPrice, buyOrder.getQty());
            } else {
                // 数量不匹配，跳过（保持原有“仅完全成交”逻辑）
                log.info("主动撮合：买单[{}]数量[{}]与卖单[{}]数量[{}]不匹配，跳过",
                        buyOrder.getClOrderId(), buyOrder.getQty(),
                        sellOrder.getClOrderId(), sellOrder.getQty());
                break;
            }
        }
    }

    /**
     * 更新订单状态到数据库
     */
    private void updateOrderStatus(Order order, OrderStatusEnum status) {
        try {
            order.setStatus(status);
            int updateCount = orderMapper.updateById(order);
            if (updateCount == 0) {
                log.error("主动撮合：订单[{}]状态更新失败（乐观锁冲突）", order.getClOrderId());
            } else {
                log.info("主动撮合：订单[{}]状态更新为[{}]", order.getClOrderId(), status.getDesc());
            }
        } catch (Exception e) {
            log.error("主动撮合：订单[{}]状态更新异常", order.getClOrderId(), e);
        }
    }

    /**
     * 判断价格是否满足撮合条件（BigDecimal版本）
     */
    private boolean isPriceMatch(SideEnum newOrderSide, BigDecimal newOrderPrice, BigDecimal counterPrice) {
        if (newOrderSide == SideEnum.BUY) {
            return newOrderPrice.compareTo(counterPrice) >= 0;
        } else {
            return newOrderPrice.compareTo(counterPrice) <= 0;
        }
    }

    /**
     * 执行完全成交逻辑
     */
    private void executeFullMatch(Order newOrder, Order counterOrder, int matchQty, BigDecimal matchPrice) {
        newOrder.setQty(0);
        counterOrder.setQty(counterOrder.getQty() - matchQty);

        boolean isOddLot = matchQty % 100 != 0;
        log.info("撮合成交（{}）：新订单[{}] vs 对手方订单[{}] | 成交价格[{}] | 成交数量[{}] | 对手方剩余[{}]",
                isOddLot ? "零股" : "整手",
                newOrder.getClOrderId(), counterOrder.getClOrderId(),
                matchPrice, matchQty, counterOrder.getQty());
    }

    /**
     * 更新新订单的最终状态（核心修改：未成交订单挂入OrderBook）
     */
    private void updateNewOrderStatus(Order newOrder, boolean isFullyMatched) {
        if (isFullyMatched) {
            newOrder.setStatus(OrderStatusEnum.FULL_FILLED); // 完全成交（终态）
        } else {
            newOrder.setStatus(OrderStatusEnum.NOT_FILLED); // 正常未成交（终态）
            // 核心操作：未成交订单添加到OrderBook，供后续订单匹配
            orderBook.addOrder(newOrder);
        }
    }
}