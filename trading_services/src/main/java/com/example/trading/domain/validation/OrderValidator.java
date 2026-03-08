package com.example.trading.domain.validation;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.SideEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 订单基础校验器
 * BigDecimal价格比较 + 买卖方向校验空指针
 * 零股校验，数量必须是100的整数倍）！！
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderValidator {
    // 合法交易市场
    private static final Set<String> VALID_MARKETS = Set.of("XSHG", "XSHE", "BJSE");

    @Value("${matching.zero-share-enable:false}")
    private boolean zeroShareEnable;

    /* 监控指标注册器 */
    private final MeterRegistry meterRegistry;
    /* 订单校验总次数计数器 */
    private Counter orderValidateTotalCounter;
    /* 订单校验通过次数计数器 */
    private Counter orderValidatePassCounter;
    /* 订单校验总失败次数计数器 */
    private Counter orderValidateFailCounter;

    @PostConstruct
    public void initMetrics() {
        // 仅初始化3个核心计数器，无细分标签
        orderValidateTotalCounter = meterRegistry.counter("trading.order.validate.total");
        orderValidatePassCounter = meterRegistry.counter("trading.order.validate.pass");
        orderValidateFailCounter = meterRegistry.counter("trading.order.validate.fail");
    }

    /**
     * 校验订单合法性
     * @return 错误信息列表
     */
    public List<ErrorCodeEnum> validate(Order order) {
        orderValidateTotalCounter.increment();
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
        if (order.getSide() != null) { // 先判断非空，避免NullPointerException
            if (SideEnum.getByCode(order.getSide().getCode()) == null) {
                errors.add(ErrorCodeEnum.SIDE_INVALID);
            }
        } else {
            errors.add(ErrorCodeEnum.PARAM_NULL);
        }

        // 4. 数量合法性
        if (order.getQty() != null) {
            // 4.1 数量必须大于0
            if (order.getQty() <= 0) {
                errors.add(ErrorCodeEnum.QTY_INVALID);
            }
            // 4.2 零股校验：禁用时强制100整数倍，启用时跳过
            else if (!zeroShareEnable && order.getQty() % 100 != 0) { // 非100倍数
                errors.add(ErrorCodeEnum.QTY_NOT_MULTIPLE_100);
                log.warn("订单[{}]数量{}非100整数倍，零股成交禁用，校验失败",
                        order.getClOrderId(), order.getQty());
            }
        }

        // 5. 价格合法性
        if (order.getPrice() != null && order.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add(ErrorCodeEnum.PRICE_INVALID);
        }

        // 6. 字段长度校验
        if (order.getClOrderId() != null && order.getClOrderId().length() != 16) {
            errors.add(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        }
        if (order.getShareholderId() != null && order.getShareholderId().length() != 10) {
            errors.add(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        }
        // 统一埋点统计：校验通过/失败
        if (errors.isEmpty()) {
            orderValidatePassCounter.increment();
        } else {
            orderValidateFailCounter.increment();
        }
        log.info("订单{}基础校验完成，错误数：{}", order.getClOrderId(), errors.size());
        return errors;
    }
}