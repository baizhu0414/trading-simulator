package com.example.trading.domain.engine;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Order;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

public class MatchingEnginTest {
    // 测试代码（可放在test目录）
    @SpringBootTest
    public class MatchingEngineTest {
        @Autowired
        private MatchingEngine matchingEngine;
        @Autowired
        private OrderBook orderBook;

        @Test
        public void testMatch() {
            // 1. 初始化卖订单（挂单：股票600030，卖价10.5，数量200）
            Order sellOrder = Order.builder()
                    .clOrderId("SELL001")
                    .market("XSHG")
                    .securityId("600030")
                    .side(SideEnum.SELL)
                    .qty(200)
                    .price(10.5)
                    .shareholderId("SH1234567890")
                    .timestamp(System.currentTimeMillis())
                    .build();
            orderBook.addOrder(sellOrder);

            // 2. 提交买订单（买单：股票600030，买价10.5，数量150）
            Order buyOrder = Order.builder()
                    .clOrderId("BUY001")
                    .market("XSHG")
                    .securityId("600030")
                    .side(SideEnum.BUY)
                    .qty(150)
                    .price(10.5)
                    .shareholderId("SH9876543210")
                    .timestamp(System.currentTimeMillis())
                    .build();

            // 3. 执行撮合
            Order matchedOrder = matchingEngine.match(buyOrder);

            // 4. 验证结果：买订单完全成交，卖订单剩余50
            Assertions.assertEquals(OrderStatusEnum.FULL_FILLED, matchedOrder.getStatus());
            Assertions.assertEquals(0, matchedOrder.getQty());
            Assertions.assertEquals(50, sellOrder.getQty());
        }
    }
}
