package com.example.trading.application;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.Order;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.domain.validation.OrderValidator;
import com.example.trading.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 交易所核心服务（流程编排）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeService {
    private final OrderValidator orderValidator;
    private final SelfTradeChecker selfTradeChecker;
    private final MatchingEngine matchingEngine;

    /**
     * 处理订单全流程：校验→风控→撮合→返回回报JSON
     * orderJson:
     *   {
     *     "clOrderId": "CL1234567890123456",
     *     "market": "XSHG",
     *     "securityId": "600030",
     *     "side": "B",
     *     "qty": 100,
     *     "price": 10.5,
     *     "shareholderId": "SH1234567890"
     *   }
     */
    public String processOrder(String orderJson) {
        // 1. JSON转订单对象
        Order order = JsonUtils.fromJson(orderJson, Order.class);
        order.setTimestamp(System.currentTimeMillis());
        order.setStatus(OrderStatusEnum.NEW);
        log.info("开始处理订单：{}", order.getClOrderId());

        // 2. 基础校验
        List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
        if (!validateErrors.isEmpty()) {
            order.setStatus(OrderStatusEnum.REJECTED);
            log.warn("订单{}基础校验失败：{}", order.getClOrderId(), validateErrors);
            return buildRejectResponse(order, validateErrors.get(0));
        }
        order.setStatus(OrderStatusEnum.VALID);

        // 3. 对敲风控检查
        ErrorCodeEnum riskError = selfTradeChecker.check(order);
        if (riskError != null) {
            order.setStatus(OrderStatusEnum.RISK_REJECT);
            log.warn("订单{}风控拦截：{}", order.getClOrderId(), riskError.getMsg());
            return buildRejectResponse(order, riskError);
        }

        // 4. 撮合引擎处理
        Order matchedOrder = matchingEngine.match(order);

        // 5. 构建成功回报
        return buildSuccessResponse(matchedOrder);
    }

    /**
     * 构建成功回报JSON
     */
    private String buildSuccessResponse(Order order) {
        return JsonUtils.toJson(order);
    }

    /**
     * 构建拒绝回报JSON
     */
    private String buildRejectResponse(Order order, ErrorCodeEnum errorCode) {
        RejectResponse rejectResponse = RejectResponse.builder()
                .clOrderId(order.getClOrderId())
                .market(order.getMarket())
                .securityId(order.getSecurityId())
                .side(order.getSide().getCode())
                .qty(order.getQty())
                .price(order.getPrice())
                .shareholderId(order.getShareholderId())
                .rejectCode(errorCode.getCode())
                .rejectText(errorCode.getMsg())
                .build();
        return JsonUtils.toJson(rejectResponse);
    }

    /**
     * 拒绝回报实体（对应题目JSON结构）
     */
    @lombok.Data
    @lombok.Builder
    private static class RejectResponse {
        private String clOrderId;
        private String market;
        private String securityId;
        private String side;
        private Integer qty;
        private Double price;
        private String shareholderId;
        private Integer rejectCode;
        private String rejectText;
    }
}