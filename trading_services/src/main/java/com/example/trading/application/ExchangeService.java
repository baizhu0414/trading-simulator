package com.example.trading.application;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.OrderBook;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.domain.validation.OrderValidator;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.util.JsonUtils;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 交易所核心服务（修复解析/异常/校验问题+幂等订单加载到OrderBook）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeService {
    private final OrderValidator orderValidator;
    private final SelfTradeChecker selfTradeChecker;
    private final MatchingEngine matchingEngine;
    private final OrderMapper orderMapper;
    private final MeterRegistry meterRegistry;
    // 核心新增：注入OrderBook，用于幂等订单加载
    private final OrderBook orderBook;

    // 监控指标
    private Counter orderTotalCounter;
    private Counter orderSuccessCounter;
    private Counter orderFailedCounter;
    private Counter orderRejectCounter;

    @PostConstruct
    public void initMetrics() {
        orderTotalCounter = meterRegistry.counter("trading.order.total");
        orderSuccessCounter = meterRegistry.counter("trading.order.success");
        orderFailedCounter = meterRegistry.counter("trading.order.failed");
        orderRejectCounter = meterRegistry.counter("trading.order.reject");

        // 核心新增：服务启动时，加载数据库中未成交订单到OrderBook（避免重启后订单簿为空）
        // OrderRecoveryService 已处理NOT_FILLED，无需重复加载
//        initUnfilledOrdersToOrderBook();
    }

    /**
     * 初始化：加载数据库中未成交订单到OrderBook
     */
    private void initUnfilledOrdersToOrderBook() {
        try {
            // 查询数据库中状态为NOT_FILLED的未成交订单
            List<Order> unfilledOrders = orderMapper.selectByStatus(OrderStatusEnum.NOT_FILLED);
            if (unfilledOrders.isEmpty()) {
                log.info("服务启动：未查询到未成交订单，无需加载到OrderBook");
                return;
            }
            // 批量添加到OrderBook
            for (Order order : unfilledOrders) {
                orderBook.addOrder(order);
            }
            log.info("服务启动：成功加载[{}]条未成交订单到OrderBook", unfilledOrders.size());
        } catch (Exception e) {
            log.error("服务启动：加载未成交订单到OrderBook失败", e);
        }
    }

    /**
     * 订单处理全流程
     */
    @Timed(value = "trading.order.process.time", description = "订单处理总耗时")
    public String processOrder(String orderJson) {
        Order order = null;
        String clOrderId = null;

        try {
            // 1. JSON解析
            order = JsonUtils.fromJson(orderJson, Order.class);
            if (order == null) {
                log.error("订单JSON解析失败，内容：{}", orderJson);
                orderFailedCounter.increment();
                return buildRejectResponse(null, ErrorCodeEnum.PARAM_FAILED);
            }
            // 补充默认值
            if (order.getCreateTime() == null) {
                order.setCreateTime(LocalDateTime.now());
            }
            if (order.getVersion() == null) {
                order.setVersion(0);
            }
            clOrderId = order.getClOrderId();

            // 2. 基础字段非空校验
            if (clOrderId == null || clOrderId.isEmpty()) {
                log.error("订单[空]clOrderId为空，参数非法");
                orderFailedCounter.increment();
                return buildRejectResponse(order, ErrorCodeEnum.PARAM_NULL);
            }

            // 3. 幂等校验（核心修改：加载已存在的未成交订单到OrderBook）
            if (orderMapper.existsByClOrderId(clOrderId)) {
                Optional<Order> existOrder = orderMapper.selectByClOrderId(clOrderId);
                if (existOrder.isPresent()) {
                    Order exist = existOrder.get();
                    // 若订单是未成交状态，加载到OrderBook
                    if (exist.getStatus() == OrderStatusEnum.NOT_FILLED) {
                        orderBook.addOrder(exist);
                        log.info("幂等校验：订单[{}]为未成交状态，已加载到OrderBook", clOrderId);
                    }
                    log.info("订单[{}]已存在（幂等校验），返回已有结果", clOrderId);
                    return JsonUtils.toJson(exist);
                }
            }

            orderTotalCounter.increment();
            // 4. 核心事务处理
            return processOrderInTransaction(order);

        } catch (IllegalArgumentException e) {
            log.error("订单[{}]参数格式错误：{}", clOrderId, e.getMessage());
            orderFailedCounter.increment();
            return buildRejectResponse(order, ErrorCodeEnum.PARAM_FORMAT_ERROR);
        } catch (Exception e) {
            log.error("订单[{}]处理全局异常", clOrderId, e);
            orderFailedCounter.increment();
            ErrorCodeEnum errorCode = ErrorCodeEnum.matchDbError(e);
            return buildRejectResponse(order, errorCode);
        }
    }

    /**
     * 事务内处理订单
     */
    @Transactional(rollbackFor = Exception.class)
    public String processOrderInTransaction(Order order) {
        String clOrderId = order.getClOrderId();
        try {
            // 初始化订单状态
            order.setStatus(OrderStatusEnum.NEW);
            orderMapper.insert(order);
            log.info("订单[{}]初始化完成，状态：NEW", clOrderId);

            // 5. 基础校验
            order.setStatus(OrderStatusEnum.PROCESSING);
            List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
            if (!validateErrors.isEmpty()) {
                ErrorCodeEnum firstError = validateErrors.get(0);
                order.setStatus(OrderStatusEnum.REJECTED);
                orderMapper.updateById(order);
                orderRejectCounter.increment();
                log.warn("订单[{}]基础校验失败：{}", clOrderId, firstError.getMsg());
                return buildRejectResponse(order, firstError);
            }

            // 6. 风控校验
            ErrorCodeEnum riskError = selfTradeChecker.check(order);
            if (riskError != null) {
                order.setStatus(OrderStatusEnum.RISK_REJECT);
                orderMapper.updateById(order);
                orderRejectCounter.increment();
                log.warn("订单[{}]风控拦截：{}", clOrderId, riskError.getMsg());
                return buildRejectResponse(order, riskError);
            }

            // 7. 撮合（零股成交）
            order.setStatus(OrderStatusEnum.MATCHING);
            Order matchedOrder = matchingEngine.match(order);
            // 更新最终状态（完全成交/正常未成交）
            if (matchedOrder.getQty() == 0) {
                matchedOrder.setStatus(OrderStatusEnum.FULL_FILLED);
            } else {
                matchedOrder.setStatus(OrderStatusEnum.NOT_FILLED);
            }
            orderMapper.updateById(matchedOrder);

            orderSuccessCounter.increment();
            log.info("订单[{}]处理完成，最终状态：{}", clOrderId, matchedOrder.getStatus().getDesc());
            return buildSuccessResponse(matchedOrder);

        } catch (Exception e) {
            ErrorCodeEnum dbError = ErrorCodeEnum.matchDbError(e);
            log.error("订单[{}]数据库操作失败，错误类型：{}，详情：", clOrderId, dbError.getMsg(), e);
            orderFailedCounter.increment();
            return buildRejectResponse(order, dbError);
        }
    }

    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(Order order) {
        return JsonUtils.toJson(order);
    }

    /**
     * 构建拒绝响应
     */
    private String buildRejectResponse(Order order, ErrorCodeEnum errorCode) {
        RejectResponse.RejectResponseBuilder builder = RejectResponse.builder()
                .rejectCode(errorCode.getCode())
                .rejectText(errorCode.getMsg());

        if (order != null) {
            builder.clOrderId(order.getClOrderId())
                    .market(order.getMarket())
                    .securityId(order.getSecurityId())
                    .side(order.getSide() != null ? order.getSide().getCode() : null)
                    .qty(order.getQty())
                    .price(order.getPrice())
                    .shareholderId(order.getShareholderId());
        }
        return JsonUtils.toJson(builder.build());
    }

    /**
     * 拒绝回报实体
     */
    @lombok.Data
    @lombok.Builder
    private static class RejectResponse {
        private String clOrderId = "";
        private String market = "";
        private String securityId = "";
        private String side = "";
        private Integer qty = 0;
        private BigDecimal price = BigDecimal.ZERO;
        private String shareholderId = "";
        private Integer rejectCode;
        private String rejectText;
    }
}