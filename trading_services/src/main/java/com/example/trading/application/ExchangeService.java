package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.application.response.OrderConfirmResponse;
import com.example.trading.application.response.RejectResponse;
import com.example.trading.application.response.TradeResponse;
import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.ResponseCodeEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
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

/**
 * 交易所核心服务（修复解析/异常/校验问题+幂等订单加载到OrderBook）
 * 新增：标准化回报 + 整合成交表逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeService {
    private final OrderValidator orderValidator;
    private final SelfTradeChecker selfTradeChecker;
    private final OrderMapper orderMapper;
    private final MeterRegistry meterRegistry;
    private final TradePersistenceService tradePersistenceService;
    private final TradeResponseHelper tradeResponseHelper;

    // 监控指标
    private Counter orderTotalCounter;
    private Counter orderSuccessCounter; // todo：统计一下
    private Counter orderFailedCounter;
    private Counter orderRejectCounter;

    @PostConstruct
    public void initMetrics() {
        orderTotalCounter = meterRegistry.counter("trading.order.total");
        orderSuccessCounter = meterRegistry.counter("trading.order.success");
        orderFailedCounter = meterRegistry.counter("trading.order.failed");
        orderRejectCounter = meterRegistry.counter("trading.order.reject");
    }

    /**
     * 订单处理全流程（被动撮合专用）
     */
    @Timed(value = "trading.order.process.time", description = "订单处理总耗时")
    public BaseResponse processOrder(String orderJson) {
        Order order = null;
        String clOrderId = null;

        try {
            // 1. JSON解析
            order = JsonUtils.fromJson(orderJson, Order.class);
            order.setOriginalQty(order.getQty()); // 设定股数初始值
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
                log.info("订单[{}]已存在（幂等校验），返回订单号已存在错误响应", clOrderId);
                orderRejectCounter.increment(); // 计入拒绝指标
                return buildRejectResponse(order, ErrorCodeEnum.ORDER_EXISTED);
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
     * 免幂等校验（主动撮合专用，不允许请求流程中手动调用！！）
     *
     * @param order 数据库中获取的订单，此时假设订单数据结构已经完全符合要求
     * @return 功能和返回值同 processOrder
     */
    public BaseResponse recoverOrder(Order order) {
        String clOrderId = order.getClOrderId();
        try {
            // 补充默认值（与 processOrder 一致）
            if (order.getCreateTime() == null) order.setCreateTime(LocalDateTime.now());
            if (order.getVersion() == null) order.setVersion(0);
            if (order.getOriginalQty() == null) order.setOriginalQty(order.getQty());

            orderTotalCounter.increment();
            // 直接进入事务处理（跳过幂等校验！）
            return processOrderInTransaction(order);

        } catch (Exception e) {
            log.error("恢复订单[{}]失败", clOrderId, e);
            orderFailedCounter.increment();
            return buildRejectResponse(order, ErrorCodeEnum.matchDbError(e));
        }
    }

    /**
     * 事务内处理订单
     */
    @Transactional(rollbackFor = Exception.class) // 新增：事务注解，保障订单&成交记录DB更新原子性
    public BaseResponse processOrderInTransaction(Order order) {
        String clOrderId = order.getClOrderId();
        try {
            // 基础校验+风控校验
            order.setStatus(OrderStatusEnum.PROCESSING);
            List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
            if (!validateErrors.isEmpty()) {
                order.setStatus(OrderStatusEnum.REJECTED);
                log.error("校验出错");
                return rejectOrder(order, validateErrors.get(0));
            }
            int insertCount = orderMapper.insert(order);
            if (insertCount > 0) {
                order.setVersion(order.getVersion()+1);
            }
            ErrorCodeEnum riskError = selfTradeChecker.check(order);
            if (riskError != null) {
                return rejectOrder(order, riskError);
            }
/*
            // 撮合（获取包含多笔匹配订单的MatchingResult）
            order.setStatus(OrderStatusEnum.MATCHING);
            MatchingResult matchingResult = matchingEngine.match(order);
            Order matchedOrder = matchingResult.getMatchedOrder();
            List<MatchingResult.MatchCounterDetail> matchDetails = matchingResult.getMatchDetails();
            // ========== 核心修复：兜底确认订单状态 ==========
            if (matchDetails.isEmpty()) {
                // 无任何匹配：确保状态为 NOT_FILLED
                matchedOrder.setStatus(OrderStatusEnum.NOT_FILLED);
            }

            // 批量构建待更新订单
            List<Order> allOrdersToUpdate = new ArrayList<>();
            allOrdersToUpdate.add(matchedOrder);
            // 批量构建成交记录
            List<Trade> trades = new ArrayList<>();
            List<OrderStatusEnum> counterStatus = new ArrayList<>();
            for (MatchingResult.MatchCounterDetail detail : matchDetails) {
                Order counterOrder = detail.getCounterPartyOrder();
                allOrdersToUpdate.add(counterOrder);

                Trade trade = buildTrade(matchedOrder, detail.getCounterPartyOrder(), detail.getExecQty(), detail.getExecPrice());
                trades.add(trade);

                counterStatus.add(counterOrder.getStatus());
            }

            // 批量插入成交记录
            if (!trades.isEmpty()) {
                tradeMapper.batchInsert(trades);
                log.info("订单[{}]批量插入{}笔成交记录", clOrderId, trades.size());
            }

            // 批量更新订单（当前订单+所有对手方订单）
            orderMapper.batchUpdateById(allOrdersToUpdate);
            log.info("订单[{}]批量更新{}笔订单", clOrderId, allOrdersToUpdate.size());

            // 清理风控缓存（完全成交的订单）
            for (Order o : allOrdersToUpdate) {
                if (o.getStatus() == OrderStatusEnum.FULL_FILLED) {
                    selfTradeChecker.removeCache(o.getShareholderId(), o.getSecurityId());
                }
                o.setVersion(o.getVersion() + 1); // 更新同步乐观锁版本号
            }

            // 构建返回结果
            return buildTradeResponse(matchedOrder, trades, counterStatus);

 */

            return tradeResponseHelper.executeOrderTransaction(order);
        } catch (Exception e) {
            log.error("订单[{}]事务失败", clOrderId, e);
            orderFailedCounter.increment();
            throw e;
        }
    }

    // 辅助：拒绝订单并更新状态
    private BaseResponse rejectOrder(Order order, ErrorCodeEnum error) {
        order.setStatus(error == ErrorCodeEnum.SELF_TRADE ? OrderStatusEnum.RISK_REJECT : OrderStatusEnum.REJECTED);
        int count = orderMapper.updateById(order);
        if (count> 0) {
            order.setVersion(order.getVersion()+1);
        }
        orderRejectCounter.increment();
        log.warn("订单[{}]拒绝：{}", order.getClOrderId(), error.getDesc());
        return buildRejectResponse(order, error);
    }

    // 辅助：构建单笔Trade对象
    public Trade buildTrade(Order orderA, Order orderB, int tradeQty, BigDecimal tradePrice) {
        Trade trade = new Trade();
        trade.setExecId(TradeResponseHelper.generateExecId());
        trade.setExecQty(tradeQty);
        trade.setExecPrice(tradePrice);
        trade.setTradeTime(LocalDateTime.now());
        trade.setMarket(orderA.getMarket());
        trade.setSecurityId(orderA.getSecurityId());

        if (orderA.getSide() == SideEnum.BUY) {
            trade.setBuyClOrderId(orderA.getClOrderId());
            trade.setSellClOrderId(orderB.getClOrderId());
            trade.setBuyShareholderId(orderA.getShareholderId());
            trade.setSellShareholderId(orderB.getShareholderId());
        } else {
            trade.setBuyClOrderId(orderB.getClOrderId());
            trade.setSellClOrderId(orderA.getClOrderId());
            trade.setBuyShareholderId(orderB.getShareholderId());
            trade.setSellShareholderId(orderA.getShareholderId());
        }
        return trade;
    }

    // 辅助：构建单笔TradeResponse
    public BaseResponse buildTradeResponse(Order matchedOrder, List<Trade> trades, List<OrderStatusEnum> counterStatus) {

        if (trades.size()==0) {
            return OrderConfirmResponse.builder()
                    .clOrderId(matchedOrder.getClOrderId())
                    .market(matchedOrder.getMarket())
                    .shareholderId(matchedOrder.getShareholderId())
                    .side(matchedOrder.getSide().getCode())
                    .qty(matchedOrder.getQty())
                    .price(matchedOrder.getPrice())
                    .shareholderId(matchedOrder.getShareholderId())
                    .orderStatus(matchedOrder.getStatus().getDesc())
                    .code(ResponseCodeEnum.ORDER_CONFIRMED.getCode())
                    .msg(ResponseCodeEnum.ORDER_CONFIRMED.getDesc()) // 未匹配成功
                    .build();
        }
        TradeResponse tradeResponse = TradeResponse.builder()
                .clOrderId(matchedOrder.getClOrderId())
                .market(matchedOrder.getMarket())
                .securityId(matchedOrder.getSecurityId())
                .side(matchedOrder.getSide().getCode())
                .orderQty(matchedOrder.getOriginalQty())
                .orderPrice(matchedOrder.getPrice())
                .shareholderId(matchedOrder.getShareholderId())
                .code(ResponseCodeEnum.TRADE_SUCCESS.getCode())
                .msg(ResponseCodeEnum.TRADE_SUCCESS.getDesc())
                .orderStatus(matchedOrder.getStatus().getDesc()).build();
        for(int i=0; i<trades.size()&& i<counterStatus.size(); i++) {
            Trade t = trades.get(i);
            tradeResponse.addTradeResponse(t.getExecId(), t.getExecQty(), t.getExecPrice(), t.getTradeTime(), counterStatus.get(i).getDesc());
        }

        return tradeResponse;
    }

    // 辅助：构建拒绝响应
    private BaseResponse buildRejectResponse(Order order, ErrorCodeEnum errorCode) {
        RejectResponse.RejectResponseBuilder builder = RejectResponse.builder()
                .code(errorCode.getCode().toString())
                .msg(errorCode.getDesc());

        if (order != null) {
            builder.clOrderId(order.getClOrderId())
                    .market(order.getMarket())
                    .securityId(order.getSecurityId())
                    .side(order.getSide() != null ? order.getSide().getCode() : "")
                    .qty(order.getQty() != null ? order.getQty() : 0)
                    .price(order.getPrice() != null ? order.getPrice() : BigDecimal.ZERO)
                    .shareholderId(order.getShareholderId());
        }
        return builder.build();
    }


}