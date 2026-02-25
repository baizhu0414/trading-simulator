package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.application.response.OrderConfirmResponse;
import com.example.trading.application.response.RejectResponse;
import com.example.trading.application.response.TradeResponse;
import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.ResponseCodeEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.result.MatchingResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.domain.validation.OrderValidator;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.util.ExecIdGenUtils;
import com.example.trading.util.JsonUtils;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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
    private final TradeResponseHelper tradeResponseHelper;
    // 异步持久化服务
    private final AsyncPersistService asyncPersistService;

    private final MatchingEngine matchingEngine;
    private final ExecutorService matchingExecutor = Executors.newSingleThreadExecutor();

    // 监控指标
    private Counter orderTotalCounter;
    private Counter orderSuccessCounter;
    private Counter orderFailedCounter;
    private Counter orderRejectCounter;

    @PostConstruct
    public void init() {
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
            final Order finalOrder = order;
            // 将撮合逻辑提交到单线程池，保证并发撮合安全
            return CompletableFuture.supplyAsync(() -> {
                return processOrderInMemory(finalOrder);
            }, matchingExecutor).join(); // 注意：join() 会阻塞，建议全链路异步

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
     * 新增：内存撮合流程（无事务，极速）
     */
    public BaseResponse processOrderInMemory(Order order) {
        String clOrderId = order.getClOrderId();
        try {
            // 1. 基础校验
            order.setStatus(OrderStatusEnum.PROCESSING);
            List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
            if (!validateErrors.isEmpty()) {
                order.setStatus(OrderStatusEnum.REJECTED);
                log.error("订单[{}]基础校验出错：{}", clOrderId, validateErrors.get(0).getDesc());
                return rejectOrderPreInsert(order, validateErrors.get(0));
            }

            // 2.风控检查提前到 Insert 之前（不符合的直接不允许入库了，并且也不放进selfCheck缓存中了）
            ErrorCodeEnum riskError = selfTradeChecker.check(order);
            if (riskError != null) {
                // 风控拦截，此时还未入库，直接返回拒绝
                log.warn("订单[{}]风控拦截，不执行入库", clOrderId);
                order.setStatus(riskError == ErrorCodeEnum.SELF_TRADE ? OrderStatusEnum.RISK_REJECT : OrderStatusEnum.REJECTED);
                return rejectOrderPreInsert(order, riskError);
            }

            // ========== 核心新增：同步插入订单到数据库（订单入库的关键步骤） ==========
            int insertCount = orderMapper.insert(order);
            if (insertCount <= 0) {
                log.error("订单[{}]同步插入数据库失败，影响行数：{}", clOrderId, insertCount);
                orderFailedCounter.increment();
                return buildRejectResponse(order, ErrorCodeEnum.DB_INSERT_FAILED);
            }
            log.info("订单[{}]同步插入数据库成功", clOrderId);
            // 插入成功后补充风控缓存（防止自成交）
            selfTradeChecker.putCache(order);

            // 3. 【核心修改】直接调用撮合引擎进行纯内存计算
            order.setStatus(OrderStatusEnum.MATCHING);
            MatchingResult matchingResult = matchingEngine.match(order); // 这里不再阻塞在数据库IO
            Order matchedOrder = matchingResult.getMatchedOrder();
            List<MatchingResult.MatchCounterDetail> matchDetails = matchingResult.getMatchDetails();

            // 4. 【内存状态更新】兜底确认状态 (逻辑从 Helper 移到这里，只为返回结果)
            List<Trade> trades = new ArrayList<>();
            List<Order> counterOrders = new ArrayList<>();
            List<OrderStatusEnum> counterStatus = new ArrayList<>();

            if (matchDetails.isEmpty()) {
                matchedOrder.setStatus(OrderStatusEnum.NOT_FILLED);
            }

            // 组装返回对象 (仅用于前端展示，不涉及DB)
            for (MatchingResult.MatchCounterDetail detail : matchDetails) {
                Order counterOrder = detail.getCounterPartyOrder();
                counterOrders.add(counterOrder);
                Trade trade = buildTrade(matchedOrder, counterOrder, detail.getExecQty(), detail.getExecPrice());
                trades.add(trade);
                counterStatus.add(counterOrder.getStatus());
            }

            // 5. 【立即返回】构建响应，不等待数据库
            BaseResponse response = buildTradeResponse(matchedOrder, trades, counterStatus);
            // 标记订单处理成功
            orderSuccessCounter.increment();

            // 6. 【异步甩锅】把刚才计算出的结果丢给异步线程去写数据库
            asyncPersistService.persistOrderAndTrades(matchedOrder, counterOrders, trades);

            log.info("订单[{}]内存撮合完成，极速返回", clOrderId);
            return response;

        } catch (Exception e) {
            log.error("订单[{}]内存处理异常", clOrderId, e);
            orderFailedCounter.increment();
            // 注意：这里如果是在 insert 之后报错，数据库里会有一条 PROCESSING 状态的脏数据
            // 需要在 OrderRecoveryService 里增加对 PROCESSING 状态超时订单的处理逻辑
            ErrorCodeEnum errorCode = ErrorCodeEnum.matchDbError(e);
            return buildRejectResponse(order, errorCode);
        }
    }

    /**
     * 辅助：拒绝订单（未入库场景，仅返回响应，不操作数据库）
     * 新增：用于 Insert 之前的拦截（基础校验/风控）
     */
    private BaseResponse rejectOrderPreInsert(Order order, ErrorCodeEnum error) {
        // 注意：这里不调用 orderMapper.updateById
        orderRejectCounter.increment();
        log.warn("订单[{}]拒绝（未入库）：{}", order.getClOrderId(), error.getDesc());
        return buildRejectResponse(order, error);
    }

    // 辅助：构建单笔Trade对象
    public Trade buildTrade(Order orderA, Order orderB, int tradeQty, BigDecimal tradePrice) {
        Trade trade = new Trade();
        trade.setExecId(ExecIdGenUtils.generateExecId());
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