package com.example.trading.domain.risk;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对敲风控检查器（检测同一股东号自买自卖）
 */
@Slf4j
@Component
public class SelfTradeChecker {
    /**
     * 风控缓存：key=shareholderId+securityId，value=订单方向
     * ConcurrentHashMap保证线程安全
     */
    private final Map<String, SideEnum> selfTradeCache = new ConcurrentHashMap<>();

    /**
     * 检查是否存在对敲交易
     * @return 错误码（null则通过）
     */
    public ErrorCodeEnum check(Order order) {
        String cacheKey = order.getShareholderId() + "_" + order.getSecurityId();
        SideEnum existSide = selfTradeCache.get(cacheKey);

        // 1. 缓存中无该股东号-股票的订单，存入缓存
        if (existSide == null) {
            selfTradeCache.put(cacheKey, order.getSide());
            log.info("订单{}风控检查通过，无对敲风险", order.getClOrderId());
            return null;
        }

        // 2. 缓存中有相反方向订单，判定为对敲
        if (!existSide.equals(order.getSide())) {
            log.warn("订单{}触发对敲风控：股东号{}，股票{}，存在相反方向订单",
                    order.getClOrderId(), order.getShareholderId(), order.getSecurityId());
            return ErrorCodeEnum.SELF_TRADE;
        }

        // 3. 同方向订单，更新缓存（不影响）
        selfTradeCache.put(cacheKey, order.getSide());
        log.info("订单{}风控检查通过，同方向订单", order.getClOrderId());
        return null;
    }

    /**
     * 订单成交/撤单后，移除风控缓存
     */
    public void removeCache(String shareholderId, String securityId) {
        String cacheKey = shareholderId + "_" + securityId;
        selfTradeCache.remove(cacheKey);
        log.info("移除风控缓存：{}", cacheKey);
    }
}