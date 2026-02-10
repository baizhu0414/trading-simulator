package com.example.trading.domain.validation;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 订单基础校验器（无业务含义的基础校验）
 */
@Slf4j
@Component
public class OrderValidator {
    // 合法交易市场
    private static final Set<String> VALID_MARKETS = Set.of("XSHG", "XSHE", "BJSE");

    /**
     * 校验订单合法性
     * @return 错误信息列表（空则校验通过）
     */
    public List<ErrorCodeEnum> validate(Order order) {
        List<ErrorCodeEnum> errors = new ArrayList<>();

        // 1. 必填字段非空校验
        if (order.getClOrderId() == null || order.getClOrderId().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (order.getMarket() == null || order.getMarket().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (order.getSecurityId() == null || order.getSecurityId().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (order.getSide() == null) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (order.getQty() == null) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (order.getPrice() == null) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }
        if (order.getShareholderId() == null || order.getShareholderId().isEmpty()) {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }

        // 2. 交易市场合法性
        if (order.getMarket() != null && !VALID_MARKETS.contains(order.getMarket())) {
            errors.add(ErrorCodeEnum.MARKET_INVALID);
        }

        // 3. 买卖方向合法性
        if (order.getSide() == null && order.getSide().getCode() != null) {
            if (SideEnum.getByCode(order.getSide().getCode()) == null) {
                errors.add(ErrorCodeEnum.SIDE_INVALID);
            }
        }

        // 4. 数量合法性
        if (order.getQty() != null && order.getQty() <= 0) {
            errors.add(ErrorCodeEnum.QTY_INVALID);
        }

        // 5. 价格合法性
        if (order.getPrice() != null && order.getPrice() < 0) {
            errors.add(ErrorCodeEnum.PRICE_INVALID);
        }

        log.info("订单{}基础校验完成，错误数：{}", order.getClOrderId(), errors.size());
        return errors;
    }
}