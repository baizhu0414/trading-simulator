package com.example.trading.controller;

import com.example.trading.application.CancelService;
import com.example.trading.application.ExchangeService;
import com.example.trading.application.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {
    private final ExchangeService exchangeService;
    private final CancelService cancelService;

    /**
     * 接收订单JSON，返回回报JSON
     */
    @PostMapping("/order")
    public BaseResponse processOrder(@RequestBody String orderJson) {
        return exchangeService.processOrder(orderJson);
    }

    @PostMapping("/cancel")
    public BaseResponse cancelOrder(@RequestBody String cancelJson) {
        // 调用应用层处理撤单请求
        return cancelService.processCancel(cancelJson);
    }
}