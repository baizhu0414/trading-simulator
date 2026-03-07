package com.example.trading.domain.engine;

import com.example.trading.domain.engine.result.MatchingResult;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Trade;
import com.example.trading.util.ExecIdGenUtils;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 撮合引擎：统一成交价生成+标准化原子操作+成交订单记录Trade+被动撮合+主动撮合+部分成交+对手方订单撤回
 * 注意：Trade 保存的唯一主体
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {
    private final OrderBook orderBook;
    private final PriceGenerator priceGenerator;
    @Value("${trading.matching.zero-share-enable:false}")
    private boolean zeroShareEnable;

    private final MeterRegistry meterRegistry;
    /* 订单簿中订单总数 */
    private AtomicInteger orderBookOrderCount;
    /* 被动撮合成交总次数计数器 */
    private Counter tradeTotalCounter;
    /* 主动撮合成交总次数计数器 */
    private Counter tradeRecoveryTotalCounter;
    /* 撤单请求总次数计数器 */
    private Counter cancelRequestTotalCounter;

    @PostConstruct
    public void initMetrics() {
        tradeTotalCounter = meterRegistry.counter("trading.trade.total");
        tradeRecoveryTotalCounter = meterRegistry.counter("trading.trade.recovery.total");
        cancelRequestTotalCounter = meterRegistry.counter("trading.cancel.request.total");
        orderBookOrderCount = new AtomicInteger(0);
        Gauge.builder("trading.orderbook.orders", orderBookOrderCount, AtomicInteger::get)
                .description("订单簿中当前订单总数")
                .register(meterRegistry);
        log.info("撮合引擎监控指标初始化完成");
    }

    /**
     * 被动撮合：新订单进入交易所的核心逻辑
     */
    /**
     * 被动撮合：新订单进入交易所的核心逻辑（修复 ConcurrentModificationException）
     */
    @Timed(value = "trading.order.match.time", description = "订单撮合耗时，在order过程中")
    public MatchingResult match(Order newOrder) {
        String securityId = newOrder.getSecurityId();
        SideEnum newOrderSide = newOrder.getSide();
        BigDecimal newOrderPrice = newOrder.getPrice();
        int newOrderRemainingQty = newOrder.getQty();
        boolean isNewOrderBuy = newOrderSide == SideEnum.BUY;

        MatchingResult matchingResult = MatchingResult.builder()
                .matchedOrder(newOrder)
                .matchDetails(new ArrayList<>())
                .build();

        // 1. 确定对手方向，获取对手方价格Map
        SideEnum oppositeSide = isNewOrderBuy ? SideEnum.SELL : SideEnum.BUY;
        TreeMap<BigDecimal, Deque<Order>> oppositePriceMap = orderBook.getPriceMap(securityId, oppositeSide);

        // ✅ 核心修复1：迭代价格集合的副本，避免直接迭代原Map触发快速失败
        List<BigDecimal> priceList = new ArrayList<>(oppositePriceMap.keySet());
        // 按价格优先顺序排序（卖单升序，买单降序）
        if (isNewOrderBuy) {
            // 买单：卖单价格升序（从低到高）
            Collections.sort(priceList);
        } else {
            // 卖单：买单价格降序（从高到低）
            Collections.sort(priceList, Collections.reverseOrder());
        }

        // 2. 遍历价格副本（价格优先）
        for (BigDecimal oppositePrice : priceList) {
            // 提前终止：主动单已完全成交
            if (newOrderRemainingQty <= 0) {
                break;
            }

            // 严格校验成交前提：买价≥卖价/卖价≤买价
            if (isNewOrderBuy) {
                if (newOrderPrice.compareTo(oppositePrice) < 0) {
                    break; // 后续价格更高，无需遍历
                }
            } else {
                if (newOrderPrice.compareTo(oppositePrice) > 0) {
                    break; // 后续价格更低，无需遍历
                }
            }

            // 从原Map获取订单队列（避免副本过期）
            Deque<Order> oppositeOrderQueue = oppositePriceMap.get(oppositePrice);
            if (oppositeOrderQueue == null || oppositeOrderQueue.isEmpty()) {
                // ✅ 直接操作原Map移除空价格节点
                oppositePriceMap.remove(oppositePrice);
                continue;
            }

            // 3. 同价格下，时间优先撮合
            while (!oppositeOrderQueue.isEmpty() && newOrderRemainingQty > 0) {
                Order oppositeOrder = oppositeOrderQueue.peek();
                int oppositeRemainingQty = oppositeOrder.getQty();

                // 零股订单直接移除
                if (oppositeRemainingQty <= 0 || (!zeroShareEnable && oppositeRemainingQty < 100)) {
                    oppositeOrderQueue.poll();
                    continue;
                }

                // 4. 计算成交数量
                int tradeQty = Math.min(newOrderRemainingQty, oppositeRemainingQty);
                if (!zeroShareEnable) {
                    tradeQty = (tradeQty / 100) * 100;
                    if (tradeQty <= 0) {
                        oppositeOrderQueue.poll();
                        continue;
                    }
                }

                // 5. 生成成交价（被动方价格）
                BigDecimal execPrice = oppositePrice;

                log.info("撮合成交：{}主动单[{}]与{}被动单[{}]，股票[{}]，成交价[{}]，成交数量[{}]",
                        newOrderSide.getDesc(), newOrder.getClOrderId(),
                        oppositeSide.getDesc(), oppositeOrder.getClOrderId(),
                        securityId, execPrice, tradeQty);

                tradeTotalCounter.increment();

                // 6. ✅ 核心修复2：修正参数错误，更新主动单（newOrder）而非被动单
                newOrderRemainingQty -= tradeQty;
                newOrder.setQty(newOrderRemainingQty);
                updateOrderStatusAndOrderBook(newOrder, newOrderRemainingQty, newOrder.getOriginalQty(), true);

                // 7. 更新被动单
                int newOppositeQty = oppositeRemainingQty - tradeQty;
                oppositeOrder.setQty(newOppositeQty);
                updateOrderStatusAndOrderBook(oppositeOrder, newOppositeQty, oppositeOrder.getOriginalQty(), false);

                // 8. 记录撮合结果
                matchingResult.addMatchDetail(oppositeOrder, tradeQty, execPrice);

                // 9. 被动单完全成交，从队列移除
                if (newOppositeQty <= 0) {
                    oppositeOrderQueue.poll();
                }
            }

            // 10. 价格队列为空，从原Map移除该价格节点
            if (oppositeOrderQueue.isEmpty()) {
                oppositePriceMap.remove(oppositePrice);
            }
        }

        // 11. 主动单未完全成交，加入订单簿
        if (newOrderRemainingQty > 0) {
            orderBook.addOrder(newOrder);
        }

        return matchingResult;
    }

    /**
     * 主动撮合：指定股票的存量订单
     */
    public List<RecoveryMatchResult> matchOrderBookOrders(String securityId) {
        log.info("开始主动撮合股票[{}]的存量订单", securityId);
        List<RecoveryMatchResult> allResults = new ArrayList<>();

        TreeMap<BigDecimal, Deque<Order>> buyPriceMap = orderBook.getPriceMap(securityId, SideEnum.BUY);
        TreeMap<BigDecimal, Deque<Order>> sellPriceMap = orderBook.getPriceMap(securityId, SideEnum.SELL);

        if (buyPriceMap.isEmpty() || sellPriceMap.isEmpty()) {
            log.info("股票[{}]订单簿无匹配的买卖订单，跳过主动撮合", securityId);
            return allResults;
        }

        Iterator<Map.Entry<BigDecimal, Deque<Order>>> buyIterator = buyPriceMap.entrySet().iterator();
        while (buyIterator.hasNext()) {
            Map.Entry<BigDecimal, Deque<Order>> buyEntry = buyIterator.next();
            BigDecimal buyPrice = buyEntry.getKey();
            Deque<Order> buyQueue = buyEntry.getValue();

            if (buyQueue.isEmpty()) {
                buyIterator.remove();
                continue;
            }

            Iterator<Map.Entry<BigDecimal, Deque<Order>>> sellIterator = sellPriceMap.entrySet().iterator();
            while (sellIterator.hasNext()) {
                Map.Entry<BigDecimal, Deque<Order>> sellEntry = sellIterator.next();
                BigDecimal sellPrice = sellEntry.getKey();
                Deque<Order> sellQueue = sellEntry.getValue(); // 按时间排序

                if (sellQueue.isEmpty()) {
                    sellIterator.remove();
                    continue;
                }

                if (buyPrice.compareTo(sellPrice) < 0) {
                    break;
                }

                List<RecoveryMatchResult> batchResults = matchOrderPairWithPartialFill(buyQueue, sellQueue, securityId);
                allResults.addAll(batchResults);

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
        return allResults;
    }

    /**
     * 更新订单状态,可能删除订单、更新状态等
     */
    private void updateOrderStatusAndOrderBook(Order order, int remainingQty, int originalQty, boolean isNewOrder) {
        if (remainingQty == 0) {
            order.setStatus(OrderStatusEnum.FULL_FILLED);
            orderBook.removeOrder(order);
        } else if (remainingQty < originalQty) {
            order.setStatus(OrderStatusEnum.PART_FILLED);
            if (isNewOrder && !orderBook.containsOrder(order)) {
                orderBook.addOrder(order);
            }
        } else {
            order.setStatus(OrderStatusEnum.NOT_FILLED);
            if (isNewOrder) {
                orderBook.addOrder(order);
            }
        }
        // 更新订单簿订单总数
        orderBookOrderCount.set(orderBook.getAllOrders().size());
    }

    /**
     * 主动撮合：订单对撮合
     */
    public List<RecoveryMatchResult> matchOrderPairWithPartialFill(Deque<Order> buyQueue, Deque<Order> sellQueue, String securityId) {
        List<RecoveryMatchResult> results = new ArrayList<>();
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

            // 计算成交数量
            int tradeQty = Math.min(buyRemainingQty, sellRemainingQty);
            if (!zeroShareEnable) {
                tradeQty = (tradeQty / 100) * 100;
                if (tradeQty <= 0) {
                    log.warn("主动撮合：买单[{}]与卖单[{}]可成交数量{}非100倍数，零股禁用，跳过",
                            buyOrder.getClOrderId(), sellOrder.getClOrderId(), tradeQty);
                    break;
                }
            }

            BigDecimal matchPrice = priceGenerator.generatePrice(buyOrder, sellOrder);

            // 更新订单剩余股数
            buyOrder.setQty(buyOrder.getQty() - tradeQty);
            sellOrder.setQty(sellOrder.getQty() - tradeQty);

            // 更新订单状态,移除orderBook队列
            if (buyOrder.getQty() == 0) {
                buyOrder.setStatus(OrderStatusEnum.FULL_FILLED);
                buyQueue.poll();
            } else {
                buyOrder.setStatus(OrderStatusEnum.PART_FILLED);
            }
            if (sellOrder.getQty() == 0) {
                sellOrder.setStatus(OrderStatusEnum.FULL_FILLED);
                sellQueue.poll();
            } else {
                sellOrder.setStatus(OrderStatusEnum.PART_FILLED);
            }

            Trade trade = new Trade();
            trade.setExecId(ExecIdGenUtils.generateExecId());
            trade.setExecQty(tradeQty);
            trade.setExecPrice(matchPrice);
            trade.setTradeTime(LocalDateTime.now());
            trade.setMarket(buyOrder.getMarket());
            trade.setSecurityId(securityId);
            if (buyOrder.getSide() == SideEnum.BUY) {
                trade.setBuyClOrderId(buyOrder.getClOrderId());
                trade.setSellClOrderId(sellOrder.getClOrderId());
                trade.setBuyShareholderId(buyOrder.getShareholderId());
                trade.setSellShareholderId(sellOrder.getShareholderId());
            }

            results.add(new RecoveryMatchResult(buyOrder, sellOrder, trade));
            tradeRecoveryTotalCounter.increment();
            log.info("主动撮合成交：买单[{}] vs 卖单[{}] | 股票[{}] | 价格[{}] | 成交数量[{}] | 零股开关：{}",
                    buyOrder.getClOrderId(), sellOrder.getClOrderId(), securityId, matchPrice, tradeQty,
                    zeroShareEnable);
        }
        return results;
    }

    // 数据载体类
    @Data
    @AllArgsConstructor
    public static class RecoveryMatchResult {
        private Order buyOrder;
        private Order sellOrder;
        private Trade trade;
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

    /**
     * 处理撤单请求：删除订单簿中的处理过程中的订单。Redis中的processing订单不需处理。
     */
    public boolean handleCancelOrder(Order order) {
        cancelRequestTotalCounter.increment();
        String clOrderId = order.getClOrderId();
        log.info("开始处理订单[{}]的全量撤单请求", clOrderId);

        boolean removeSuccess = orderBook.removeOrder(order);
        if (removeSuccess) {
            log.info("订单[{}]已从订单簿全量移除，撤单完成", clOrderId);
        } else {
            log.error("订单[{}]从订单簿全量移除失败", clOrderId);
        }

        return removeSuccess;
    }

    /**
     * 暴露订单簿实例给对账服务
     */
    public OrderBook getOrderBook() {
        return this.orderBook;
    }
}