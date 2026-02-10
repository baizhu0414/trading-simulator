package com.example.trading.domain.engine;

import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 订单簿
 * 核心特性：
 * 1. 买队列（BUY）：价格降序排列（高价优先），同价格按时间戳升序；
 * 2. 卖队列（SELL）：价格升序排列（低价优先），同价格按时间戳升序；
 * 3. 全线程安全：基于ConcurrentSkipListMap + LinkedBlockingQueue实现，无显式锁；
 * 4. 按股票代码隔离订单簿，避免跨股票撮合。
 */
@Slf4j
@Component
public class OrderBook {
    /**
     * 订单簿核心存储结构：
     * - 第一层Key：securityId（股票代码）
     * - 第二层Key：SideEnum（买卖方向）
     * - 第三层：ConcurrentSkipListMap（价格有序Map），Key=价格，Value=该价格下的订单队列
     */
    private final ConcurrentMap<String, ConcurrentMap<SideEnum, ConcurrentSkipListMap<Double, Queue<Order>>>> orderBookMap =
            new ConcurrentHashMap<>();

    /**
     * 初始化指定股票的订单簿（首次访问时自动初始化）
     */
    private void initOrderBook(String securityId) {
        orderBookMap.computeIfAbsent(securityId, key -> {
            // 初始化买卖方向的价格有序Map
            ConcurrentMap<SideEnum, ConcurrentSkipListMap<Double, Queue<Order>>> sideMap = new ConcurrentHashMap<>();

            // 买队列：价格降序（高价优先），Comparator.reverseOrder()实现降序
            sideMap.put(SideEnum.BUY, new ConcurrentSkipListMap<>(Comparator.reverseOrder()));
            // 卖队列：价格升序（低价优先），自然序（默认）
            sideMap.put(SideEnum.SELL, new ConcurrentSkipListMap<>(Comparator.naturalOrder()));

            log.info("初始化股票[{}]的订单簿", securityId);
            return sideMap;
        });
    }

    /**
     * 添加订单到订单簿（线程安全）
     * 逻辑：按「股票+方向+价格」分层存储，同价格订单按时间戳排队
     */
    public void addOrder(Order order) {
        if (order == null || order.getSecurityId() == null || order.getSide() == null) {
            log.error("订单参数非法，无法添加到订单簿：{}", order);
            return;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        double price = order.getPrice();

        // 1. 初始化订单簿（若未初始化）
        initOrderBook(securityId);

        // 2. 获取该股票+方向的价格有序Map
        ConcurrentSkipListMap<Double, Queue<Order>> priceMap = orderBookMap.get(securityId).get(side);

        // 3. 按价格获取/创建订单队列（LinkedBlockingQueue保证线程安全）
        Queue<Order> orderQueue = priceMap.computeIfAbsent(price, k -> new LinkedBlockingQueue<>());

        // 4. 添加订单到队列（LinkedBlockingQueue的offer方法线程安全）
        boolean added = orderQueue.offer(order);
        if (added) {
            log.info("订单[{}]已加入[{}]方向订单簿，股票[{}]，价格[{}]，队列长度[{}]",
                    order.getClOrderId(), side.getDesc(), securityId, price, orderQueue.size());
        } else {
            log.error("订单[{}]添加到订单簿失败：队列已满", order.getClOrderId());
        }
    }

    /**
     * 获取指定股票+方向的价格有序Map（用于撮合引擎匹配最优价格）
     */
    public ConcurrentSkipListMap<Double, Queue<Order>> getPriceMap(String securityId, SideEnum side) {
        initOrderBook(securityId);
        return orderBookMap.get(securityId).get(side);
    }

    /**
     * 从订单簿移除指定订单（线程安全）
     */
    public boolean removeOrder(Order order) {
        if (order == null || order.getSecurityId() == null || order.getSide() == null) {
            log.error("订单参数非法，无法从订单簿移除：{}", order);
            return false;
        }

        String securityId = order.getSecurityId();
        SideEnum side = order.getSide();
        double price = order.getPrice();

        // 1. 校验订单簿是否存在
        if (!orderBookMap.containsKey(securityId)) {
            log.warn("股票[{}]的订单簿不存在，无法移除订单[{}]", securityId, order.getClOrderId());
            return false;
        }

        // 2. 获取价格Map和订单队列
        ConcurrentSkipListMap<Double, Queue<Order>> priceMap = orderBookMap.get(securityId).get(side);
        Queue<Order> orderQueue = priceMap.get(price);
        if (orderQueue == null || orderQueue.isEmpty()) {
            log.warn("订单[{}]对应的价格[{}]队列不存在/为空，无法移除", order.getClOrderId(), price);
            return false;
        }

        // 3. 移除订单（LinkedBlockingQueue的remove方法线程安全）
        boolean removed = orderQueue.removeIf(o -> o.getClOrderId().equals(order.getClOrderId()));

        // 4. 若队列空，移除该价格节点（避免空队列占用内存）
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
     * 清空指定股票的订单簿（测试/重置时使用）
     */
    public void clearOrderBook(String securityId) {
        if (orderBookMap.containsKey(securityId)) {
            orderBookMap.get(securityId).get(SideEnum.BUY).clear();
            orderBookMap.get(securityId).get(SideEnum.SELL).clear();
            log.info("股票[{}]的订单簿已清空", securityId);
        }
    }
}