package com.example.trading.domain.engine;

import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 成交价生成器（扩展版）
 * 支持3种可配置的价格生成策略：
 * 1. MID_PRICE：中间价（(买价+卖价)/2）；
 * 2. BUY_PRICE：买方价格；
 * 3. SELL_PRICE：卖方价格。
 */
@Slf4j
@Component
public class PriceGenerator {
    // 从配置文件读取价格生成策略（默认中间价）
    @Value("${trading.matching.price-strategy:MID_PRICE}")
    private String priceStrategy;

    /**
     * 生成成交价格（线程安全）
     * @param buyOrder 买订单（可能为新订单/对手方订单，需先判定）
     * @param sellOrder 卖订单（同上）
     * @return 最终成交价（保留2位小数）
     */
    public double generatePrice(Order buyOrder, Order sellOrder) {
        // 第一步：确定买/卖订单（兼容新订单是买/卖的情况）
        Order realBuyOrder = buyOrder.getSide() == SideEnum.BUY ? buyOrder : sellOrder;
        Order realSellOrder = sellOrder.getSide() == SideEnum.SELL ? sellOrder : buyOrder;

        if (realBuyOrder == null || realSellOrder == null) {
            log.error("买/卖订单为空，无法生成成交价：buyOrder={}, sellOrder={}", buyOrder, sellOrder);
            return 0.0;
        }

        double buyPrice = realBuyOrder.getPrice();
        double sellPrice = realSellOrder.getPrice();
        double finalPrice = 0.0;

        // 第二步：按策略生成价格
        switch (priceStrategy.toUpperCase()) {
            case "MID_PRICE":
                // 中间价（默认）
                finalPrice = (buyPrice + sellPrice) / 2;
                break;
            case "BUY_PRICE":
                // 买方价格
                finalPrice = buyPrice;
                break;
            case "SELL_PRICE":
                // 卖方价格
                finalPrice = sellPrice;
                break;
            default:
                // 未知策略，默认中间价
                log.warn("未知的价格生成策略[{}]，使用默认中间价", priceStrategy);
                finalPrice = (buyPrice + sellPrice) / 2;
                break;
        }

        // 第三步：保留2位小数（符合证券交易价格精度）
        finalPrice = Math.round(finalPrice * 100.0) / 100.0;
        log.info("生成成交价：策略[{}] | 买价[{}] | 卖价[{}] | 成交价[{}]",
                priceStrategy, buyPrice, sellPrice, finalPrice);

        return finalPrice;
    }
}