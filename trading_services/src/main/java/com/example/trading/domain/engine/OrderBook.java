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
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;

/**
 * 订单簿
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBook {
    /**
     * 第一层Key：securityId（股票代码）
     * 第二层Key：SideEnum（买卖方向）
     * 第三层：ConcurrentSkipListMap（价格有序Map），Key=BigDecimal价格，Value=该价格下的订单队列
     * 示例：
     * orderBookMap（ConcurrentMap）
     * ├─ Key: securityId（如600030）→ 股票维度（第一层）
     * │  └─ Value: HashMap<SideEnum, TreeMap<BigDecimal, Deque<Order>>>
     * │     ├─ Key: SideEnum.BUY → 买卖方向（第二层）
     * │     │  └─ Value: TreeMap<BigDecimal, Deque<Order>>（价格降序）
     * │     │     ├─ Key: 10.50 → 价格维度（第三层）
     * │     │     │  └─ Value: Deque<Order> → 该价格下的买单队列（时间优先）
     * │     │     └─ Key: 10.40
     * │     └─ Key: SideEnum.SELL → 买卖方向（第二层）
     * │        └─ Value: TreeMap<BigDecimal, Deque<Order>>（价格升序，时间升序）
     * │           ├─ Key: 10.50 → 价格维度（第三层）
     * │           └─ Key: 10.60
     * └─ Key: 600016 → 另一支股票（第一层）
     *    └─ ...
     */
    private final Map<String, Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>>> orderBookMap = new ConcurrentHashMap<>();
    // 根据股票ID分片处理，不会再出现多个线程访问同一股票的冲突情况了！！！
//    private final ConcurrentMap<String, ConcurrentMap<SideEnum, ConcurrentSkipListMap<BigDecimal, PriorityBlockingQueue<Order>>>> orderBookMap =
//            new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;

    /**
     * 统一 BigDecimal 价格精度为 2 位小数，否则可能价格一致的订单配对不上。
     */
    private BigDecimal normalizePrice(BigDecimal price) {
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /* 订单簿买队列总订单数 */
    private Gauge buyQueueSizeGauge;
    /* 订单簿卖队列总订单数 */
    private Gauge sellQueueSizeGauge;

    @PostConstruct
    public void initMetrics() {
        buyQueueSizeGauge = Gauge.builder("trading.orderbook.buy.total_size", () -> {
            int totalBuySize = 0;
            for (Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>> sideMap : orderBookMap.values()) {
                TreeMap<BigDecimal, Deque<Order>> buyPriceMap = sideMap.get(SideEnum.BUY);
                if (buyPriceMap != null) {
                    for (Deque<Order> orderQueue : buyPriceMap.values()) {
                        totalBuySize += orderQueue.size();
                    }
                }
            }
            return totalBuySize;
        }).description("订单簿买队列总订单数").register(meterRegistry);

        sellQueueSizeGauge = Gauge.builder("trading.orderbook.sell.total_size", () -> {
            int totalSellSize = 0;
            for (Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>> sideMap : orderBookMap.values()) {
                TreeMap<BigDecimal, Deque<Order>> sellPriceMap = sideMap.get(SideEnum.SELL);
                if (sellPriceMap != null) {
                    for (Deque<Order> orderQueue : sellPriceMap.values()) {
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
        // 对并发敏感，可能执行到一半正在修改，后面的任务进来触发BUG。
        orderBookMap.computeIfAbsent(securityId, key -> {
            Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>> sideMap = new HashMap<>();
            // 买队列：价格降序
            sideMap.put(SideEnum.BUY, new TreeMap<>(Comparator.reverseOrder()));
            // 卖队列：价格升序
            sideMap.put(SideEnum.SELL, new TreeMap<>());

            log.info("初始化股票[{}]的订单簿", securityId);
            return sideMap;
        });
    }

    /**
     * 添加订单到订单簿
     */
    public void addOrder(Order order) {
        // 1. 基础参数校验
        if (order == null || order.getSecurityId() == null || order.getSide() == null) {
            log.error("订单参数非法，无法添加到订单簿：{}", order);
            return;
        }
        // 2. 订单状态校验：仅允许可撮合状态的订单加入
        if (order.getStatus() != OrderStatusEnum.NOT_FILLED
                && order.getStatus() != OrderStatusEnum.MATCHING
                && order.getStatus() != OrderStatusEnum.PART_FILLED) {
            log.error("订单[{}]状态非法（{}），无法加入订单簿", order.getClOrderId(), order.getStatus().getDesc());
            return;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        BigDecimal normalizedPrice = normalizePrice(order.getPrice());
        // 3. 初始化订单簿结构
        initOrderBook(securityId);
        TreeMap<BigDecimal, Deque<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Deque<Order> orderQueue = priceMap.computeIfAbsent(normalizedPrice, k -> new ArrayDeque<>());

        // 4. 添加订单到优先队列
        orderQueue.addLast(order);
        log.info("订单[{}]已加入[{}]方向订单簿，股票[{}]，价格[{}]，队列长度[{}]",
                order.getClOrderId(), side.getDesc(), securityId, normalizedPrice, orderQueue.size());
    }

    /**
     * 验证订单是否在队列中且排序正确
     */
    private void verifyOrderInQueue(Order order, PriorityBlockingQueue<Order> orderQueue) {
        try {
            // 订单是否在队列中
            boolean found = orderQueue.stream().anyMatch(o -> order.getClOrderId().equals(o.getClOrderId()));
            if (!found) {
                log.error("【严重】订单[{}]加入后验证失败：不在队列中！队列内容：{}",
                        order.getClOrderId(),
                        orderQueue.stream().map(Order::getClOrderId).collect(Collectors.toList()));
                return;
            }

            // 队列的排序是否正确
            List<Order> firstThree = orderQueue.stream().limit(3).collect(Collectors.toList());
            log.debug("订单[{}]加入后，队列前{}个订单排序：{}",
                    order.getClOrderId(), firstThree.size(),
                    firstThree.stream().map(o -> o.getClOrderId() + "(" + o.getCreateTime() + ")").collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("【严重】订单[{}]加入后验证异常", order.getClOrderId(), e);
        }
    }

    /**
     * 获取指定股票+方向的价格有序Map
     */
    public TreeMap<BigDecimal, Deque<Order>> getPriceMap(String securityId, SideEnum side) {
        initOrderBook(securityId);
        return orderBookMap.get(securityId).get(side);
    }

    /**
     * 从订单簿移除指定订单
     */
    public boolean removeOrder(Order order) {
        // 基础参数校验
        if (order == null || order.getSecurityId() == null || order.getSide() == null) {
            log.error("订单参数非法，无法从订单簿移除：{}", order);
            return false;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        BigDecimal normalizedPrice = normalizePrice(order.getPrice());

        // 校验订单簿是否存在
        if (!orderBookMap.containsKey(securityId)) {
            log.warn("股票[{}]的订单簿不存在，无法移除订单[{}]", securityId, order.getClOrderId());
            return false;
        }

        // 获取价格Map和订单队列
        TreeMap<BigDecimal, Deque<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Deque<Order> orderQueue = priceMap.get(normalizedPrice);
        if (orderQueue == null || orderQueue.isEmpty()) {
            log.warn("订单[{}]对应的价格[{}]队列不存在/为空，无法移除", order.getClOrderId(), normalizedPrice);
            return false;
        }

        // 移除订单
        boolean removed = orderQueue.removeIf(o -> o.getClOrderId().equals(order.getClOrderId()));

        // 若队列空，移除该价格节点
        if (removed && orderQueue.isEmpty()) {
            priceMap.remove(normalizedPrice);
            log.info("订单[{}]移除后，价格[{}]队列已空，移除该价格节点", order.getClOrderId(), normalizedPrice);
        }

        if (removed) {
            log.info("订单[{}]已从[{}]方向订单簿移除，股票[{}]，价格[{}]",
                    order.getClOrderId(), side.getDesc(), securityId, normalizedPrice);
        } else {
            log.warn("订单[{}]不存在于[{}]方向订单簿，股票[{}]，价格[{}]",
                    order.getClOrderId(), side.getDesc(), securityId, normalizedPrice);
        }
        return removed;
    }

    /**
     * 判断订单是否存在于订单簿中
     */
    public boolean containsOrder(Order order) {
        // 基础参数校验
        if (order == null || order.getSecurityId() == null || order.getSide() == null || order.getPrice() == null || order.getClOrderId() == null) {
            log.error("订单参数非法，无法检查是否存在于订单簿：{}", order);
            return false;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        // 检查时也统一价格精度
        BigDecimal normalizedPrice = normalizePrice(order.getPrice());
        String clOrderId = order.getClOrderId();

        // 校验订单簿是否初始化
        if (!orderBookMap.containsKey(securityId)) {
            log.warn("股票[{}]的订单簿未初始化，订单[{}]不存在", securityId, clOrderId);
            return false;
        }

        // 获取该方向的价格Map和对应价格的订单队列
        TreeMap<BigDecimal, Deque<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Deque<Order> orderQueue = priceMap.get(normalizedPrice);

        // 队列不存在/为空 → 订单不存在
        if (orderQueue == null || orderQueue.isEmpty()) {
            log.info("订单[{}]对应的价格[{}]队列不存在/为空，判定不存在于订单簿", clOrderId, normalizedPrice);
            return false;
        }

        // 遍历队列，通过clOrderId判断订单是否存在
        boolean exists = orderQueue.stream()
                .anyMatch(o -> clOrderId.equals(o.getClOrderId()));

        if (exists) {
            log.info("订单[{}]存在于[{}]方向订单簿，股票[{}]，价格[{}]", clOrderId, side.getDesc(), securityId, normalizedPrice);
        } else {
            log.warn("订单[{}]不存在于[{}]方向订单簿，股票[{}]，价格[{}]", clOrderId, side.getDesc(), securityId, normalizedPrice);
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

    /**
     * 根据订单号查询订单簿中的订单
     */
    public Order findOrderByClOrderId(String clOrderId) {
        if (clOrderId == null || clOrderId.isEmpty()) {
            log.error("订单号为空，无法查询订单");
            return null;
        }

        // 遍历所有股票的订单簿
        for (Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>> sideMap : orderBookMap.values()) {
            // 遍历买卖方向
            for (TreeMap<BigDecimal, Deque<Order>> priceMap : sideMap.values()) {
                // 遍历所有价格队列
                for (Deque<Order> orderQueue : priceMap.values()) {
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

    /**
     * 获取订单簿中所有订单
     */
    public List<Order> getAllOrders() {
        List<Order> allOrders = new ArrayList<>();

        // 遍历所有股票的订单簿（如果此处修改，别处也修改就会出错。）
        for (Map.Entry<String, Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>>> securityEntry : orderBookMap.entrySet()) {
            String securityId = securityEntry.getKey();
            Map<SideEnum, TreeMap<BigDecimal, Deque<Order>>> sideMap = securityEntry.getValue();

            // 遍历买卖方向
            for (Map.Entry<SideEnum, TreeMap<BigDecimal, Deque<Order>>> sideEntry : sideMap.entrySet()) {
                SideEnum side = sideEntry.getKey();
                TreeMap<BigDecimal, Deque<Order>> priceMap = sideEntry.getValue();

                // 遍历价格层级
                for (Map.Entry<BigDecimal, Deque<Order>> priceEntry : priceMap.entrySet()) {
                    BigDecimal price = priceEntry.getKey();
                    Deque<Order> orderQueue = priceEntry.getValue();

                    // 遍历价格下的所有订单
                    List<Order> queueOrders = orderQueue.stream().collect(Collectors.toList());
                    allOrders.addAll(queueOrders);

                    log.debug("股票[{}]方向[{}]价格[{}]下有{}笔订单", securityId, side.getDesc(), price, queueOrders.size());
                }
            }
        }

        log.info("订单簿中总计查询到{}笔订单（对账用）", allOrders.size());
        return Collections.unmodifiableList(allOrders);
    }
}