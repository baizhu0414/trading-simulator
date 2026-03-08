package com.example.trading.domain.engine;

import com.example.trading.domain.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 连续竞价成交价生成器
 */
@Slf4j
@Component
public class PriceGenerator {

    /**
     * 根据连续竞价规则生成成交价
     * @param takerOrder 新进入的订单
     * @param makerOrder 对手方最优订单
     * @return 最终成交价
     */
    public BigDecimal generatePrice(Order takerOrder, Order makerOrder) {
        // 连续竞价成交价规则：
        // 成交价 = 被动方（已在订单簿中）的价格
        BigDecimal execPrice = makerOrder.getPrice();
        return execPrice.setScale(2, RoundingMode.HALF_UP);
    }
}