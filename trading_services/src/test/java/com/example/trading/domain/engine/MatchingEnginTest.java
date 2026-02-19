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

        }
    }
}
