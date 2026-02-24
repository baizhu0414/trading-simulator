package com.example.trading.domain.validation;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.CancelOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 撤单请求基础校验器（简化版：添加已撤销订单缓存）
 */
@Slf4j
@Component
public class CancelValidator {
    private static final Set<String> VALID_MARKETS = Set.of("XSHG", "XSHE", "BJSE");

    // ========== 核心新增：已撤销订单缓存（key=origClOrderId，value=true=已撤销） ==========
    private final ConcurrentHashMap<String, Boolean> canceledOrderCache = new ConcurrentHashMap<>();

    /**
     * 校验撤单请求合法性
     */
    public List<ErrorCodeEnum> validate(CancelOrder cancelOrder) {
        List<ErrorCodeEnum> errors = new ArrayList<>();
        String origClOrderId = cancelOrder.getOrigClOrderId();

        // ========== 核心新增：先查缓存，快速拦截已撤销订单 ==========
        if (canceledOrderCache.containsKey(origClOrderId)) {
            log.warn("撤单请求失败：原订单[{}]已撤销（缓存命中）", origClOrderId);
            errors.add(ErrorCodeEnum.ORDER_NOT_CANCELABLE);
            return errors;
        }

        // 1. 必填字段非空校验（仅保留 origClOrderId 及其他必要字段）
        if (origClOrderId == null || origClOrderId.isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (cancelOrder.getMarket() == null || cancelOrder.getMarket().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (cancelOrder.getSecurityId() == null || cancelOrder.getSecurityId().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (cancelOrder.getShareholderId() == null || cancelOrder.getShareholderId().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (cancelOrder.getSide() == null) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }

        // 2. 字段长度校验（仅保留 origClOrderId）
        if (origClOrderId != null && origClOrderId.length() != 16) {
            errors.add(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        }
        if (cancelOrder.getShareholderId() != null && cancelOrder.getShareholderId().length() != 10) {
            errors.add(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        }
        if (cancelOrder.getMarket() != null && cancelOrder.getMarket().length() != 4) {
            errors.add(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        }
        if (cancelOrder.getSecurityId() != null && cancelOrder.getSecurityId().length() != 6) {
            errors.add(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        }

        // 3. 交易市场合法性校验
        if (cancelOrder.getMarket() != null && !VALID_MARKETS.contains(cancelOrder.getMarket())) {
            errors.add(ErrorCodeEnum.MARKET_INVALID);
        }

        // 4. 买卖方向合法性校验
        if (cancelOrder.getSide() != null && SideEnum.getByCode(cancelOrder.getSide().getCode()) == null) {
            errors.add(ErrorCodeEnum.SIDE_INVALID);
        }

        log.info("撤单请求[原订单：{}]基础校验完成，错误数：{}", origClOrderId, errors.size());
        return errors;
    }

    /**
     * 撤单成功后，将订单号加入缓存
     */
    public void markOrderAsCanceled(String origClOrderId) {
        canceledOrderCache.put(origClOrderId, true);
        log.info("已将订单[{}]加入已撤销缓存", origClOrderId);
    }
}