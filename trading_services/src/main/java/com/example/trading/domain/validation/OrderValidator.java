package com.example.trading.domain.validation;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 订单基础校验器（无业务含义的基础校验）
 * 修复：BigDecimal价格比较 + 买卖方向校验空指针 + 逻辑优化
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

        // 3. 买卖方向合法性（核心修复：空指针+逻辑错误）
        if (order.getSide() != null) { // 先判断非空，避免NullPointerException
            if (SideEnum.getByCode(order.getSide().getCode()) == null) {
                errors.add(ErrorCodeEnum.SIDE_INVALID);
            }
        }

        // 4. 数量合法性
        if (order.getQty() != null && order.getQty() <= 0) {
            errors.add(ErrorCodeEnum.QTY_INVALID);
        }

        // 5. 价格合法性（核心修复：BigDecimal不能直接用<，改用compareTo）
        if (order.getPrice() != null && order.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add(ErrorCodeEnum.PRICE_INVALID);
        }

        // 补充到OrderValidator的validate方法中
        if (order.getClOrderId() != null && order.getClOrderId().length() != 16) {
            errors.add(ErrorCodeEnum.PARAM_FAILED);
        }
        if (order.getShareholderId() != null && order.getShareholderId().length() != 10) {
            errors.add(ErrorCodeEnum.PARAM_FAILED);
        }

        log.info("订单{}基础校验完成，错误数：{}", order.getClOrderId(), errors.size());
        return errors;
    }
}