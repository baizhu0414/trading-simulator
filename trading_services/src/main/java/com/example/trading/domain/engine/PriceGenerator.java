package com.example.trading.domain.engine;

import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成交价生成器（BigDecimal版本，解决类型不匹配+精度问题）
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
     * 生成成交价格（线程安全，BigDecimal版本）
     * @param newOrder 新订单（兼容买/卖方向）
     * @param counterOrder 对手方订单（兼容买/卖方向）
     * @return 最终成交价（保留2位小数，RoundingMode.HALF_UP四舍五入）
     */
    // 核心修改1：参数改为newOrder/counterOrder（匹配MatchingEngine的调用），返回值改为BigDecimal
    public BigDecimal generatePrice(Order newOrder, Order counterOrder) {
        // 第一步：确定买/卖订单（兼容新订单是买/卖的情况）
        Order realBuyOrder = newOrder.getSide() == SideEnum.BUY ? newOrder : counterOrder;
        Order realSellOrder = counterOrder.getSide() == SideEnum.SELL ? counterOrder : newOrder;

        if (realBuyOrder == null || realSellOrder == null) {
            log.error("买/卖订单为空，无法生成成交价：newOrder={}, counterOrder={}", newOrder, counterOrder);
            return BigDecimal.ZERO; // 替换0.0为BigDecimal.ZERO
        }

        // 核心修改2：价格变量改为BigDecimal（直接获取Order的BigDecimal价格）
        BigDecimal buyPrice = realBuyOrder.getPrice();
        BigDecimal sellPrice = realSellOrder.getPrice();
        BigDecimal finalPrice = BigDecimal.ZERO;

        // 第二步：按策略生成价格（BigDecimal算术运算，替代double）
        switch (priceStrategy.toUpperCase()) {
            case "MID_PRICE":
                // 中间价：(买价+卖价)/2，BigDecimal除法需指定精度和舍入模式
                finalPrice = buyPrice.add(sellPrice)
                        .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                break;
            case "BUY_PRICE":
                // 买方价格
                finalPrice = buyPrice.setScale(2, RoundingMode.HALF_UP);
                break;
            case "SELL_PRICE":
                // 卖方价格
                finalPrice = sellPrice.setScale(2, RoundingMode.HALF_UP);
                break;
            default:
                // 未知策略，默认中间价
                log.warn("未知的价格生成策略[{}]，使用默认中间价", priceStrategy);
                finalPrice = buyPrice.add(sellPrice)
                        .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                break;
        }

        // 第三步：确保最终价格保留2位小数（证券交易精度要求）
        finalPrice = finalPrice.setScale(2, RoundingMode.HALF_UP);
        log.info("生成成交价：策略[{}] | 买价[{}] | 卖价[{}] | 成交价[{}]",
                priceStrategy, buyPrice, sellPrice, finalPrice);

        return finalPrice;
    }
}