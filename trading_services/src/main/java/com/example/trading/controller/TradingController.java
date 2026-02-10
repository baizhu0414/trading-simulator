package com.example.trading.controller;

import com.example.trading.application.ExchangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易控制器（接口层）
 */
@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {
    private final ExchangeService exchangeService;

    /**
     * 接收订单JSON，返回回报JSON
     */
    @PostMapping("/order")
    public String processOrder(@RequestBody String orderJson) {
        return exchangeService.processOrder(orderJson);
    }
}