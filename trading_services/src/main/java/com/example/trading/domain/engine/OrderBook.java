package com.example.trading.domain.engine;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 订单簿（修正价格类型为BigDecimal，解决类型不匹配+精度丢失问题）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBook {
    /**
     * 核心修改1：价格类型从Double改为BigDecimal
     * 第一层Key：securityId（股票代码）
     * 第二层Key：SideEnum（买卖方向）
     * 第三层：ConcurrentSkipListMap（价格有序Map），Key=BigDecimal价格，Value=该价格下的订单队列
     * 示例：
     * orderBookMap（ConcurrentMap）
     * ├─ Key: securityId（如600030）→ 股票维度（第一层）
     * │  └─ Value: ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, Queue<Order>>>
     * │     ├─ Key: SideEnum.BUY → 买卖方向（第二层）
     * │     │  └─ Value: ConcurrentSkipListMap<BigDecimal, Queue<Order>>（价格降序）
     * │     │     ├─ Key: 10.50 → 价格维度（第三层）
     * │     │     │  └─ Value: Queue<Order> → 该价格下的买单队列（时间优先）
     * │     │     └─ Key: 10.40
     * │     └─ Key: SideEnum.SELL → 买卖方向（第二层）
     * │        └─ Value: ConcurrentSkipListMap<BigDecimal, Queue<Order>>（价格升序）
     * │           ├─ Key: 10.50 → 价格维度（第三层）
     * │           └─ Key: 10.60
     * └─ Key: 600016 → 另一支股票（第一层）
     *    └─ ...
     */
    private final ConcurrentMap<String, ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, Queue<Order>>>> orderBookMap =
            new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;
    private Gauge buyQueueSizeGauge;
    private Gauge sellQueueSizeGauge;

    @PostConstruct
    public void initMetrics() {
        buyQueueSizeGauge = Gauge.builder("trading.orderbook.buy.total_size", () -> {
            int totalBuySize = 0;
            for (ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, Queue<Order>>> sideMap : orderBookMap.values()) {
                // 核心修改2：价格Map类型同步改为BigDecimal
                ConcurrentSkipListMap<BigDecimal, Queue<Order>> buyPriceMap = sideMap.get(SideEnum.BUY);
                if (buyPriceMap != null) {
                    for (Queue<Order> orderQueue : buyPriceMap.values()) {
                        totalBuySize += orderQueue.size();
                    }
                }
            }
            return totalBuySize;
        }).description("订单簿买队列总订单数").register(meterRegistry);

        sellQueueSizeGauge = Gauge.builder("trading.orderbook.sell.total_size", () -> {
            int totalSellSize = 0;
            for (ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, Queue<Order>>> sideMap : orderBookMap.values()) {
                // 核心修改3：价格Map类型同步改为BigDecimal
                ConcurrentSkipListMap<BigDecimal, Queue<Order>> sellPriceMap = sideMap.get(SideEnum.SELL);
                if (sellPriceMap != null) {
                    for (Queue<Order> orderQueue : sellPriceMap.values()) {
                        totalSellSize += orderQueue.size();
                    }
                }
            }
            return totalSellSize;
        }).description("订单簿卖队列总订单数").register(meterRegistry);

        log.info("订单簿监控指标初始化完成");
    }

    /**
     * 初始化指定股票的订单簿
     */
    private void initOrderBook(String securityId) {
        orderBookMap.computeIfAbsent(securityId, key -> {
            ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, Queue<Order>>> sideMap = new ConcurrentHashMap<>();

            // 核心修改4：BigDecimal的Comparator（替代double的Comparator）
            // 买队列：价格降序（高价优先），使用BigDecimal的compareTo方法
            sideMap.put(SideEnum.BUY, new ConcurrentSkipListMap<>(Comparator.reverseOrder()));
            // 卖队列：价格升序（低价优先），自然序（BigDecimal默认按数值比较）
            sideMap.put(SideEnum.SELL, new ConcurrentSkipListMap<>(Comparator.naturalOrder()));

            log.info("初始化股票[{}]的订单簿", securityId);
            return sideMap;
        });
    }

    /**
     * 添加订单到订单簿（解决类型不匹配核心行）
     */
    public void addOrder(Order order) {
        if (order == null || order.getSecurityId() == null || order.getSide() == null) {
            log.error("订单参数非法，无法添加到订单簿：{}", order);
            return;
        }

        // 核心新增：状态校验，仅允许NOT_FILLED/MATCHING/PART_FILLED订单加入（可继续撮合）
        if (order.getStatus() != OrderStatusEnum.NOT_FILLED
                && order.getStatus() != OrderStatusEnum.MATCHING
                && order.getStatus() != OrderStatusEnum.PART_FILLED) {
            log.error("订单[{}]状态非法（{}），无法加入订单簿", order.getClOrderId(), order.getStatus().getDesc());
            return;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        BigDecimal price = order.getPrice();

        initOrderBook(securityId);
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Queue<Order> orderQueue = priceMap.computeIfAbsent(price, k -> new LinkedBlockingQueue<>());

        boolean added = orderQueue.offer(order);
        if (added) {
            log.info("订单[{}]已加入[{}]方向订单簿，股票[{}]，价格[{}]，队列长度[{}]",
                    order.getClOrderId(), side.getDesc(), securityId, price, orderQueue.size());
        } else {
            log.error("订单[{}]添加到订单簿失败：队列已满", order.getClOrderId());
        }
    }

    /**
     * 获取指定股票+方向的价格有序Map（返回BigDecimal类型）
     */
    // 核心修改6：返回值类型改为BigDecimal
    public ConcurrentSkipListMap<BigDecimal, Queue<Order>> getPriceMap(String securityId, SideEnum side) {
        initOrderBook(securityId);
        return orderBookMap.get(securityId).get(side);
    }

    /**
     * 从订单簿移除指定订单
     */
    public boolean removeOrder(Order order) {
        if (order == null || order.getSecurityId() == null || order.getSide() == null) {
            log.error("订单参数非法，无法从订单簿移除：{}", order);
            return false;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        // 核心修改7：直接获取BigDecimal类型的price
        BigDecimal price = order.getPrice();

        // 1. 校验订单簿是否存在
        if (!orderBookMap.containsKey(securityId)) {
            log.warn("股票[{}]的订单簿不存在，无法移除订单[{}]", securityId, order.getClOrderId());
            return false;
        }

        // 2. 获取价格Map和订单队列（BigDecimal类型）
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Queue<Order> orderQueue = priceMap.get(price);
        if (orderQueue == null || orderQueue.isEmpty()) {
            log.warn("订单[{}]对应的价格[{}]队列不存在/为空，无法移除", order.getClOrderId(), price);
            return false;
        }

        // 3. 移除订单
        boolean removed = orderQueue.removeIf(o -> o.getClOrderId().equals(order.getClOrderId()));

        // 4. 若队列空，移除该价格节点
        if (removed && orderQueue.isEmpty()) {
            priceMap.remove(price);
            log.info("订单[{}]移除后，价格[{}]队列已空，移除该价格节点", order.getClOrderId(), price);
        }

        if (removed) {
            log.info("订单[{}]已从[{}]方向订单簿移除，股票[{}]，价格[{}]",
                    order.getClOrderId(), side.getDesc(), securityId, price);
        } else {
            log.warn("订单[{}]不存在于[{}]方向订单簿，股票[{}]，价格[{}]",
                    order.getClOrderId(), side.getDesc(), securityId, price);
        }
        return removed;
    }

    /**
     * 判断订单是否存在于订单簿中
     * @param order 待检查的订单
     * @return true=存在，false=不存在/参数非法
     */
    public boolean containsOrder(Order order) {
        // 1. 基础参数校验（与add/remove逻辑保持一致）
        if (order == null || order.getSecurityId() == null || order.getSide() == null || order.getPrice() == null || order.getClOrderId() == null) {
            log.error("订单参数非法，无法检查是否存在于订单簿：{}", order);
            return false;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        BigDecimal price = order.getPrice();
        String clOrderId = order.getClOrderId();

        // 2. 校验订单簿是否初始化该股票
        if (!orderBookMap.containsKey(securityId)) {
            log.warn("股票[{}]的订单簿未初始化，订单[{}]不存在", securityId, clOrderId);
            return false;
        }

        // 3. 获取该方向的价格Map和对应价格的订单队列
        ConcurrentSkipListMap<BigDecimal, Queue<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Queue<Order> orderQueue = priceMap.get(price);

        // 4. 队列不存在/为空 → 订单不存在
        if (orderQueue == null || orderQueue.isEmpty()) {
            log.info("订单[{}]对应的价格[{}]队列不存在/为空，判定不存在于订单簿", clOrderId, price);
            return false;
        }

        // 5. 遍历队列，通过clOrderId（唯一标识）判断订单是否存在
        // 注：使用stream+anyMatch，兼顾线程安全和可读性（LinkedBlockingQueue的迭代器是线程安全的）
        boolean exists = orderQueue.stream()
                .anyMatch(o -> clOrderId.equals(o.getClOrderId()));

        if (exists) {
            log.info("订单[{}]存在于[{}]方向订单簿，股票[{}]，价格[{}]", clOrderId, side.getDesc(), securityId, price);
        } else {
            log.warn("订单[{}]不存在于[{}]方向订单簿，股票[{}]，价格[{}]", clOrderId, side.getDesc(), securityId, price);
        }
        return exists;
    }

    /**
     * 清空指定股票的订单簿
     */
    public void clearOrderBook(String securityId) {
        if (orderBookMap.containsKey(securityId)) {
            orderBookMap.get(securityId).get(SideEnum.BUY).clear();
            orderBookMap.get(securityId).get(SideEnum.SELL).clear();
            log.info("股票[{}]的订单簿已清空", securityId);
        }
    }

    // 在现有OrderBook类中新增以下方法
    /**
     * 根据订单号查询订单簿中的订单
     * @param clOrderId 订单唯一编号
     * @return 订单对象（不存在返回null）
     */
    public Order findOrderByClOrderId(String clOrderId) {
        if (clOrderId == null || clOrderId.isEmpty()) {
            log.error("订单号为空，无法查询订单");
            return null;
        }

        // 遍历所有股票的订单簿
        for (ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, Queue<Order>>> sideMap : orderBookMap.values()) {
            // 遍历买卖方向
            for (ConcurrentSkipListMap<BigDecimal, Queue<Order>> priceMap : sideMap.values()) {
                // 遍历所有价格队列
                for (Queue<Order> orderQueue : priceMap.values()) {
                    // 匹配订单号
                    Order matchOrder = orderQueue.stream()
                            .filter(o -> clOrderId.equals(o.getClOrderId()))
                            .findFirst()
                            .orElse(null);
                    if (matchOrder != null) {
                        return matchOrder;
                    }
                }
            }
        }

        log.warn("订单[{}]未在订单簿中找到", clOrderId);
        return null;
    }
}