package com.example.trading.domain.engine;

import com.example.trading.application.TradePersistenceService;
import com.example.trading.application.TradeResponseHelper;
import com.example.trading.domain.engine.result.MatchingResult;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Trade;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 撮合引擎（统一成交价生成+标准化原子操作+成交订单记录Trade+被动撮合+部分成交+对手方订单撤回）
 * 注意：Trade 保存的唯一主体
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {
    private final OrderBook orderBook;
    private final PriceGenerator priceGenerator;
    // 【修改】移除 TradePersistenceService 依赖，引擎只负责内存计算
    // private final TradePersistenceService tradePersistenceService;
    @Value("${matching.zero-share-enable:false}")
    private boolean zeroShareEnable;

    private final MeterRegistry meterRegistry;

    /*增加监测指标*/
    private Counter tradeTotalCounter;

    @PostConstruct
    public void initMetrics() {
        tradeTotalCounter = meterRegistry.counter("trading.trade.total");
    }

    /**
     * 被动撮合：新订单入站。核心撮合逻辑（支持部分成交+成交记录插入+事务保障+修复乐观锁）
     * 更新OrderBook缓存
     */
    @Timed(value = "trading.order.match.time", description = "订单撮合耗时")
    public MatchingResult match(Order newOrder) {
        String securityId = newOrder.getSecurityId();
        SideEnum newOrderSide = newOrder.getSide();
        BigDecimal newOrderPrice = newOrder.getPrice();
        int newOrderRemainingQty = newOrder.getQty();

        MatchingResult matchingResult = MatchingResult.builder()
                .matchedOrder(newOrder)
                .matchDetails(new java.util.ArrayList<>())
                .build();

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

            // 3. 逐个撮合对手订单(可能一条订单匹配多个对手订单，直到完全成交或者对手订单没了或者对手没有符合条件的)
            while (!oppositeOrderQueue.isEmpty() && newOrderRemainingQty > 0) {
                Order oppositeOrder = oppositeOrderQueue.peek();
                int oppositeRemainingQty = oppositeOrder.getQty();

                if (oppositeRemainingQty <= 0) {
                    oppositeOrderQueue.poll();
                    continue;
                }

                // 4. 计算成交数量
                int tradeQty = Math.min(newOrderRemainingQty, oppositeRemainingQty);
                // 禁用零股时，成交数量向下取整为100的整数倍
                if (!zeroShareEnable) {
                    tradeQty = (tradeQty / 100) * 100;
                    if (tradeQty <= 0) {
                        log.warn("订单[{}]与对手单[{}]可成交数量{}非100倍数，零股禁用，跳过撮合",
                                newOrder.getClOrderId(), oppositeOrder.getClOrderId(), tradeQty);
                        break;
                    }
                }

                BigDecimal execPrice = priceGenerator.generatePrice(newOrder, oppositeOrder); // 统一调用成交价生成算法，与主动撮合保持一致

                log.info("撮合成交：{}单[{}]与{}单[{}]，股票[{}]，价格[{}]，成交数量[{}]，零股开关：{}",
                        newOrderSide.getDesc(), newOrder.getClOrderId(),
                        oppositeSide.getDesc(), oppositeOrder.getClOrderId(),
                        securityId, oppositePrice, tradeQty, zeroShareEnable);

                tradeTotalCounter.increment(); // 监测

                // 5. 更新新订单剩余数量、状态及缓存
                newOrderRemainingQty -= tradeQty;
                newOrder.setQty(newOrderRemainingQty);
                updateOrderStatusAndOrderBook(newOrder, newOrderRemainingQty, newOrder.getOriginalQty());
//                if (newOrder.getQty()< newOrder.getOriginalQty()) {
//                    newOrder.setStatus(OrderStatusEnum.PART_FILLED);
//                    orderBook.addOrder(newOrder);
//                } else if (newOrder.getQty()==0){
//                    newOrder.setStatus(OrderStatusEnum.FULL_FILLED); // 成交后不需要放入缓存中了，后面入库即可。
//                } else {
//                    newOrder.setStatus(OrderStatusEnum.NOT_FILLED);
//                    orderBook.addOrder(newOrder);
//                }

                // 6. 更新对手订单剩余数量（核心修复：先查最新version，避免乐观锁冲突）
                int newOppositeQty = oppositeRemainingQty - tradeQty;
                oppositeOrder.setQty(newOppositeQty);
                updateOrderStatusAndOrderBook(oppositeOrder, newOppositeQty, oppositeOrder.getOriginalQty());
//                if(newOppositeQty == 0) {
//                    oppositeOrder.setStatus(OrderStatusEnum.FULL_FILLED);
//                    orderBook.removeOrder(oppositeOrder);
//                } else {
//                    oppositeOrder.setStatus(OrderStatusEnum.PART_FILLED); // 对手订单在OrderBook中，因此不需要重复添加orderbook
//                }

                matchingResult.addMatchDetail(oppositeOrder, tradeQty, execPrice);
                // 正反方的订单表order更新\selfChecker更新都在ExchangeService中
            }// end while
        } // end for
        // 如果没有对手方，循环结束后没有任何处理！订单状态仍为 MATCHING，且未加入 OrderBook
        if (newOrderRemainingQty == newOrder.getOriginalQty()) {
            // 没有任何成交：更新状态为 NOT_FILLED，加入 OrderBook
            updateOrderStatusAndOrderBook(newOrder, newOrderRemainingQty, newOrder.getOriginalQty());
        }


        return matchingResult;
    }

    /**
     * 主动撮合：指定股票的存量订单（修复部分成交逻辑+成交记录插入+事务保障）
     * 如果希望主动对已有OrderBook中的订单进行一次完整撮合，可以调用。
     */
    public List<RecoveryMatchResult> matchOrderBookOrders(String securityId) {
        log.info("开始主动撮合股票[{}]的存量订单", securityId);
        List<RecoveryMatchResult> allResults = new ArrayList<>();

        ConcurrentSkipListMap<BigDecimal, Queue<Order>> buyPriceMap = orderBook.getPriceMap(securityId, SideEnum.BUY);
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> sellPriceMap = orderBook.getPriceMap(securityId, SideEnum.SELL);

        if (buyPriceMap.isEmpty() || sellPriceMap.isEmpty()) {
            log.info("股票[{}]订单簿无匹配的买卖订单，跳过主动撮合", securityId);
            return allResults;
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

                // 调用支持零股开关的撮合方法
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
     * 更新订单状态和OrderBook
     */
    private void updateOrderStatusAndOrderBook(Order order, int remainingQty, int originalQty) {
        if (remainingQty == 0) {
            order.setStatus(OrderStatusEnum.FULL_FILLED);
            orderBook.removeOrder(order);
        } else if (remainingQty < originalQty) {
            order.setStatus(OrderStatusEnum.PART_FILLED);
            // 部分成交且不在订单簿时才添加（避免重复添加）
            if (!orderBook.containsOrder(order)) {
                orderBook.addOrder(order);
            }
        } else {
            order.setStatus(OrderStatusEnum.NOT_FILLED);
            orderBook.addOrder(order);
        }
    }

    /**
     * 更新成交记录和订单记录，原子操作。
     */
//    @Transactional(rollbackFor = Exception.class) // 新增：事务注解，保障主动撮合的原子性
    public List<RecoveryMatchResult> matchOrderPairWithPartialFill(Queue<Order> buyQueue, Queue<Order> sellQueue, String securityId) {
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

            // 计算成交数量（零股开关控制）
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
            trade.setExecId(TradeResponseHelper.generateExecId());
            trade.setExecQty(tradeQty);
            trade.setExecPrice(matchPrice);
            trade.setTradeTime(LocalDateTime.now());
            trade.setMarket(securityId); // 假设 market 就是 securityId 或者从 order 获取
            trade.setSecurityId(securityId);
            // 填充买卖方ID
            if (buyOrder.getSide() == SideEnum.BUY) {
                trade.setBuyClOrderId(buyOrder.getClOrderId());
                trade.setSellClOrderId(sellOrder.getClOrderId());
                trade.setBuyShareholderId(buyOrder.getShareholderId());
                trade.setSellShareholderId(sellOrder.getShareholderId());
            }

            results.add(new RecoveryMatchResult(buyOrder, sellOrder, trade));

            log.info("主动撮合成交：买单[{}] vs 卖单[{}] | 股票[{}] | 价格[{}] | 成交数量[{}] | 零股开关：{}",
                    buyOrder.getClOrderId(), sellOrder.getClOrderId(), securityId, matchPrice, tradeQty,
                    zeroShareEnable);
        }
        return results;
    }

    // 这是一个简单的数据载体类，用于在恢复阶段传递“内存撮合的成果”
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
     * 处理撤单请求：原子性全量移除订单（修改版：不支持部分撤单）
     * 保证与撮合逻辑的线程安全，避免并发竞态，只要校验通过直接全量移除订单
     * @param order 待撤订单
     * @return true=全量移除成功，false=移除失败
     */
    public boolean handleCancelOrder(Order order) {
        String clOrderId = order.getClOrderId();
        log.info("开始处理订单[{}]的全量撤单请求", clOrderId);

        // 1. 校验订单是否在订单簿中
        Order orderInBook = orderBook.findOrderByClOrderId(clOrderId);
        if (orderInBook == null) {
            log.warn("订单[{}]不在订单簿中，无法执行全量撤单", clOrderId);
            return false;
        }

        // 2. 原子性全量从订单簿移除订单（无部分移除逻辑，直接移除整个订单）
        boolean removeSuccess = orderBook.removeOrder(order);
        if (removeSuccess) {
            log.info("订单[{}]已从订单簿全量移除，撤单完成", clOrderId);
        } else {
            log.error("订单[{}]从订单簿全量移除失败", clOrderId);
        }

        return removeSuccess;
    }

    /**
     * 新增：暴露订单簿实例给对账服务
     */
    public OrderBook getOrderBook() {
        return this.orderBook;
    }
}