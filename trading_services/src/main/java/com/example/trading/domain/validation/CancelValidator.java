package com.example.trading.domain.validation;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.CancelOrder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 撤单请求基础校验器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelValidator {
    private static final Set<String> VALID_MARKETS = Set.of("XSHG", "XSHE", "BJSE");

    /* 监控指标注册器 */
    private final MeterRegistry meterRegistry;
    /* 撤单校验总次数计数器 */
    private Counter cancelValidateTotalCounter;
    /* 撤单校验通过次数计数器 */
    private Counter cancelValidatePassCounter;
    /* 撤单校验总失败次数计数器 */
    private Counter cancelValidateFailCounter;

    @PostConstruct
    public void initMetrics() {
        // 仅初始化3个核心计数器，无细分标签
        cancelValidateTotalCounter = meterRegistry.counter("trading.cancel.validate.total");
        cancelValidatePassCounter = meterRegistry.counter("trading.cancel.validate.pass");
        cancelValidateFailCounter = meterRegistry.counter("trading.cancel.validate.fail");
    }

    /**
     * 校验撤单请求合法性
     */
    public List<ErrorCodeEnum> validate(CancelOrder cancelOrder) {
        cancelValidateTotalCounter.increment();
        List<ErrorCodeEnum> errors = new ArrayList<>();
        String origClOrderId = cancelOrder.getOrigClOrderId();

        // 必填字段非空校验
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

        // 字段长度校验
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

        // 交易市场合法性校验
        if (cancelOrder.getMarket() != null && !VALID_MARKETS.contains(cancelOrder.getMarket())) {
            errors.add(ErrorCodeEnum.MARKET_INVALID);
        }

        // 买卖方向合法性校验
        if (cancelOrder.getSide() != null && SideEnum.getByCode(cancelOrder.getSide().getCode()) == null) {
            errors.add(ErrorCodeEnum.SIDE_INVALID);
        }

        // 统一埋点统计：校验通过/失败
        if (errors.isEmpty()) {
            cancelValidatePassCounter.increment();
        } else {
            cancelValidateFailCounter.increment();
        }

        log.info("撤单请求[原订单：{}]基础校验完成，错误数：{}", origClOrderId, errors.size());
        return errors;
    }

}