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
 * 撮合引擎（适配BigDecimal价格+部分成交+支持零股成交+未成交订单挂单）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {
    private final OrderBook orderBook;
    private final OrderMapper orderMapper;
    private final PriceGenerator priceGenerator;

    /**
     * 核心撮合逻辑（支持部分成交，修复乐观锁）
     */
    @Timed(value = "trading.order.match.time", description = "订单撮合耗时")
    public Order match(Order newOrder) {
        String securityId = newOrder.getSecurityId();
        SideEnum newOrderSide = newOrder.getSide();
        BigDecimal newOrderPrice = newOrder.getPrice();
        int newOrderRemainingQty = newOrder.getQty();

        // 1. 确定对手方向
        SideEnum oppositeSide = newOrderSide == SideEnum.BUY ? SideEnum.SELL : SideEnum.BUY;
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> oppositePriceMap = orderBook.getPriceMap(securityId, oppositeSide);

        // 2. 遍历对手方价格队列
        for (BigDecimal oppositePrice : oppositePriceMap.keySet()) {
            if (!isPriceMatch(newOrderSide, newOrderPrice, oppositePrice)) {
                break;
            }

            Queue<Order> oppositeOrderQueue = oppositePriceMap.get(oppositePrice);
            if (oppositeOrderQueue == null || oppositeOrderQueue.isEmpty()) {
                continue;
            }

            // 3. 逐个撮合对手订单
            while (!oppositeOrderQueue.isEmpty() && newOrderRemainingQty > 0) {
                Order oppositeOrder = oppositeOrderQueue.peek();
                int oppositeRemainingQty = oppositeOrder.getQty();

                if (oppositeRemainingQty <= 0) {
                    oppositeOrderQueue.poll();
                    continue;
                }

                // 4. 计算成交数量
                int tradeQty = Math.min(newOrderRemainingQty, oppositeRemainingQty);
                log.info("撮合成交：{}单[{}]与{}单[{}]，股票[{}]，价格[{}]，成交数量[{}]",
                        newOrderSide.getDesc(), newOrder.getClOrderId(),
                        oppositeSide.getDesc(), oppositeOrder.getClOrderId(),
                        securityId, oppositePrice, tradeQty);

                // 5. 更新新订单剩余数量
                newOrderRemainingQty -= tradeQty;
                newOrder.setQty(newOrderRemainingQty);

                // 6. 更新对手订单剩余数量（核心修复：先查最新version，避免乐观锁冲突）
                int newOppositeQty = oppositeRemainingQty - tradeQty;
                oppositeOrder.setQty(newOppositeQty);

                // 关键：先查询数据库中对手单的最新version
                Order latestOppositeOrder = orderMapper.selectByClOrderId(oppositeOrder.getClOrderId()).orElse(null);
                if (latestOppositeOrder != null) {
                    oppositeOrder.setVersion(latestOppositeOrder.getVersion()); // 同步最新version
                }

                // 更新对手单状态
                if (newOppositeQty == 0) {
                    oppositeOrder.setStatus(OrderStatusEnum.FULL_FILLED);
                    oppositeOrderQueue.poll();
                } else {
                    oppositeOrder.setStatus(OrderStatusEnum.PART_FILLED);
                }

                // 7. 持久化对手订单（增加更新行数日志）
                try {
                    int updateCount = orderMapper.updateById(oppositeOrder);
                    if (updateCount > 0) {
                        log.info("对手单[{}]更新成功：原始数量{}，剩余数量{}，状态{}，更新行数{}",
                                oppositeOrder.getClOrderId(), oppositeOrder.getOriginalQty(),
                                newOppositeQty, oppositeOrder.getStatus().getDesc(), updateCount);
                        // 同步订单簿中订单的version（避免后续更新再次冲突）
                        oppositeOrder.setVersion(oppositeOrder.getVersion() + 1);
                    } else {
                        log.error("对手单[{}]更新失败：乐观锁冲突（version不匹配），当前version={}，建议重试",
                                oppositeOrder.getClOrderId(), oppositeOrder.getVersion());
                        throw new RuntimeException("对手单更新失败（乐观锁冲突）");
                    }
                } catch (Exception e) {
                    log.error("更新对手单[{}]到数据库失败", oppositeOrder.getClOrderId(), e);
                    throw new RuntimeException("撮合对手单更新失败", e);
                }
            }
        }

        // 8. 新订单未完全成交 → 加入订单簿
        if (newOrderRemainingQty > 0) {
            orderBook.addOrder(newOrder);
        }

        return newOrder;
    }

    /**
     * 主动撮合指定股票的存量订单（修复部分成交逻辑）
     */
    @Transactional(rollbackFor = Exception.class)
    public void matchOrderBookOrders(String securityId) {
        log.info("开始主动撮合股票[{}]的存量订单", securityId);

        ConcurrentSkipListMap<BigDecimal, Queue<Order>> buyPriceMap = orderBook.getPriceMap(securityId, SideEnum.BUY);
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> sellPriceMap = orderBook.getPriceMap(securityId, SideEnum.SELL);

        if (buyPriceMap.isEmpty() || sellPriceMap.isEmpty()) {
            log.info("股票[{}]订单簿无匹配的买卖订单，跳过主动撮合", securityId);
            return;
        }

        // 遍历买单（价格降序）
        Iterator<Map.Entry<BigDecimal, Queue<Order>>> buyIterator = buyPriceMap.entrySet().iterator();
        while (buyIterator.hasNext()) {
            Map.Entry<BigDecimal, Queue<Order>> buyEntry = buyIterator.next();
            BigDecimal buyPrice = buyEntry.getKey();
            Queue<Order> buyQueue = buyEntry.getValue();

            if (buyQueue.isEmpty()) {
                buyIterator.remove();
                continue;
            }

            // 遍历卖单（价格升序）
            Iterator<Map.Entry<BigDecimal, Queue<Order>>> sellIterator = sellPriceMap.entrySet().iterator();
            while (sellIterator.hasNext()) {
                Map.Entry<BigDecimal, Queue<Order>> sellEntry = sellIterator.next();
                BigDecimal sellPrice = sellEntry.getKey();
                Queue<Order> sellQueue = sellEntry.getValue();

                if (sellQueue.isEmpty()) {
                    sellIterator.remove();
                    continue;
                }

                if (buyPrice.compareTo(sellPrice) < 0) {
                    break;
                }

                // 修复：调用支持部分成交的撮合方法
                matchOrderPairWithPartialFill(buyQueue, sellQueue, securityId);

                if (sellQueue.isEmpty()) {
                    sellIterator.remove();
                }
                if (buyQueue.isEmpty()) {
                    break;
                }
            }

            if (buyQueue.isEmpty()) {
                buyIterator.remove();
            }
        }

        log.info("股票[{}]存量订单主动撮合完成", securityId);
    }

    /**
     * 匹配一对买卖订单队列（支持部分成交，修复核心逻辑）
     */
    private void matchOrderPairWithPartialFill(Queue<Order> buyQueue, Queue<Order> sellQueue, String securityId) {
        while (!buyQueue.isEmpty() && !sellQueue.isEmpty()) {
            Order buyOrder = buyQueue.peek();
            Order sellOrder = sellQueue.peek();

            if (buyOrder == null || sellOrder == null) {
                break;
            }

            int buyRemainingQty = buyOrder.getQty();
            int sellRemainingQty = sellOrder.getQty();
            if (buyRemainingQty <= 0 || sellRemainingQty <= 0) {
                if (buyRemainingQty <= 0) buyQueue.poll();
                if (sellRemainingQty <= 0) sellQueue.poll();
                continue;
            }

            // 计算成交数量（支持部分成交）
            int tradeQty = Math.min(buyRemainingQty, sellRemainingQty);
            BigDecimal matchPrice = priceGenerator.generatePrice(buyOrder, sellOrder);

            // 执行成交（修复数量更新逻辑）
            executePartialMatch(buyOrder, sellOrder, tradeQty, matchPrice);

            // 更新买单状态
            if (buyOrder.getQty() == 0) {
                buyOrder.setStatus(OrderStatusEnum.FULL_FILLED);
                buyQueue.poll();
            } else {
                buyOrder.setStatus(OrderStatusEnum.PART_FILLED);
            }

            // 更新卖单状态
            if (sellOrder.getQty() == 0) {
                sellOrder.setStatus(OrderStatusEnum.FULL_FILLED);
                sellQueue.poll();
            } else {
                sellOrder.setStatus(OrderStatusEnum.PART_FILLED);
            }

            // 持久化更新（同步最新version）
            updateOrderToDb(buyOrder);
            updateOrderToDb(sellOrder);

            log.info("主动撮合成交：买单[{}] vs 卖单[{}] | 股票[{}] | 价格[{}] | 成交数量[{}] | 买单剩余[{}] | 卖单剩余[{}]",
                    buyOrder.getClOrderId(), sellOrder.getClOrderId(), securityId, matchPrice, tradeQty,
                    buyOrder.getQty(), sellOrder.getQty());
        }
    }

    /**
     * 执行部分成交逻辑（修复原executeFullMatch的错误）
     */
    private void executePartialMatch(Order buyOrder, Order sellOrder, int matchQty, BigDecimal matchPrice) {
        // 更新买单剩余数量
        buyOrder.setQty(buyOrder.getQty() - matchQty);
        // 更新卖单剩余数量
        sellOrder.setQty(sellOrder.getQty() - matchQty);

        boolean isOddLot = matchQty % 100 != 0;
        log.info("撮合成交（{}）：买单[{}] vs 卖单[{}] | 成交价格[{}] | 成交数量[{}] | 买单剩余[{}] | 卖单剩余[{}]",
                isOddLot ? "零股" : "整手",
                buyOrder.getClOrderId(), sellOrder.getClOrderId(),
                matchPrice, matchQty, buyOrder.getQty(), sellOrder.getQty());
    }

    /**
     * 通用订单更新方法（处理乐观锁）
     */
    private void updateOrderToDb(Order order) {
        try {
            // 同步最新version
            Order latestOrder = orderMapper.selectByClOrderId(order.getClOrderId()).orElse(null);
            if (latestOrder != null) {
                order.setVersion(latestOrder.getVersion());
            }
            int updateCount = orderMapper.updateById(order);
            if (updateCount > 0) {
                log.info("订单[{}]更新成功，状态{}，剩余数量{}，更新行数{}",
                        order.getClOrderId(), order.getStatus().getDesc(), order.getQty(), updateCount);
                order.setVersion(order.getVersion() + 1); // 同步version
            } else {
                log.error("订单[{}]更新失败：乐观锁冲突，当前version={}", order.getClOrderId(), order.getVersion());
            }
        } catch (Exception e) {
            log.error("订单[{}]更新异常", order.getClOrderId(), e);
        }
    }

    /**
     * 判断价格是否满足撮合条件
     */
    private boolean isPriceMatch(SideEnum newOrderSide, BigDecimal newOrderPrice, BigDecimal counterPrice) {
        if (newOrderSide == SideEnum.BUY) {
            return newOrderPrice.compareTo(counterPrice) >= 0;
        } else {
            return newOrderPrice.compareTo(counterPrice) <= 0;
        }
    }

}