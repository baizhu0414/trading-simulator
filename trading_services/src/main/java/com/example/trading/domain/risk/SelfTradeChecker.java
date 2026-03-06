package com.example.trading.domain.risk;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对敲风控检查器，检测同一股东号自买自卖
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
     * 原子性检查对敲并更新缓存
     * @return 存在对敲返回错误码，否则返回null
     */
    public ErrorCodeEnum checkAndUpdateCache(Order order) {
        String cacheKey = buildKey(order);
        SideEnum currentSide = order.getSide();
        // 用数组标记是否触发对敲（lambda需final变量，用数组绕开限制）
        boolean[] isSelfTrade = {false};

        // compute() 保证原子性：同一key的检查+更新不可分割
        selfTradeCache.compute(cacheKey, (key, existSide) -> {
            if (existSide != null) {
                if (!existSide.equals(currentSide)) {
                    // 【关键】存在相反方向订单，标记对敲，且不修改缓存（保留原方向）
                    isSelfTrade[0] = true;
                    return existSide;
                }
                // 同方向：覆盖更新为当前订单方向（逻辑上同方向可覆盖，不影响风控）
            }
            // 无缓存/同方向：写入当前订单方向
            return currentSide;
        });

        if (isSelfTrade[0]) {
            log.warn("订单{}触发对敲风控：股东号{}，股票{}，存在相反方向订单",
                    order.getClOrderId(), order.getShareholderId(), order.getSecurityId());
            return ErrorCodeEnum.SELF_TRADE;
        }

        log.info("订单{}风控检查通过，已记入缓存", order.getClOrderId());
        return null;
    }

    /**
     * 主动撮合专用，不允许其他地方使用。订单确认生效后，主动写入/更新缓存
     */
    public void putCache(Order order) {
        String cacheKey = buildKey(order);
        // 仅当缓存不存在且订单非终态时才设置
        if (order.getStatus() != null && !order.getStatus().isFinalStatus()) {
            SideEnum existing = selfTradeCache.putIfAbsent(cacheKey, order.getSide());
            if (existing == null) {
                log.info("订单{}已记入风控缓存（恢复）", order.getClOrderId());
            } else {
                log.info("订单{}风控缓存已存在，跳过覆盖（恢复）", order.getClOrderId());
            }
        }
    }

    /**
     * 订单成交/撤单后，移除风控缓存
     */
    public void removeCache(String shareholderId, String securityId) {
        String cacheKey = shareholderId + "_" + securityId;
        selfTradeCache.remove(cacheKey);
        log.info("移除风控缓存：{}", cacheKey);
    }

    private String buildKey(Order order) {
        return order.getShareholderId() + "_" + order.getSecurityId();
    }
}