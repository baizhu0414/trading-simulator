package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.application.response.OrderConfirmResponse;
import com.example.trading.application.response.RejectResponse;
import com.example.trading.application.response.TradeResponse;
import com.example.trading.common.RedisConstant;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.example.trading.config.ShardingMatchingExecutor;

/**
 * 交易所核心服务
 */
@Slf4j
@Service
//@RequiredArgsConstructor
public class ExchangeService {
    private final OrderValidator orderValidator;
    private final SelfTradeChecker selfTradeChecker;
    private final OrderMapper orderMapper;
    private final OrderIdempotentService orderIdempotentService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    // 异步持久化服务
    private final AsyncPersistService asyncPersistService;

    private final MatchingEngine matchingEngine;
    /**
     * 通过{com.example.trading.config.AsyncConfig#matchingExecutor()}解决OOM隐患。
     * 同时根绝订单ID分片并发进行撮合
     */
    private final ShardingMatchingExecutor shardingMatchingExecutor;

    /* 订单请求总次数计数器 */
    private Counter orderTotalCounter;
    /* 订单成功次数计数器 */
    private Counter orderSuccessCounter;
    /* 订单处理失败次数计数器 */
    private Counter orderFailedCounter;
    /* 订单被拒绝次数计数器 */
    private Counter orderRejectCounter;

    @PostConstruct
    public void init() {
        orderTotalCounter = meterRegistry.counter("trading.order.total");
        orderSuccessCounter = meterRegistry.counter("trading.order.success");
        orderFailedCounter = meterRegistry.counter("trading.order.failed");
        orderRejectCounter = meterRegistry.counter("trading.order.reject");
    }

    // 手动编写构造函数，在参数上添加 @Qualifier
    public ExchangeService(
            OrderValidator orderValidator,
            SelfTradeChecker selfTradeChecker,
            OrderMapper orderMapper,
            OrderIdempotentService orderIdempotentService,
            MeterRegistry meterRegistry,
            AsyncPersistService asyncPersistService,
            MatchingEngine matchingEngine,
            StringRedisTemplate redisTemplate,
            @Qualifier("shardingMatchingExecutor") final ShardingMatchingExecutor shardingMatchingExecutor) { // 这里指定Bean名称
        this.orderValidator = orderValidator;
        this.selfTradeChecker = selfTradeChecker;
        this.orderMapper = orderMapper;
        this.orderIdempotentService = orderIdempotentService;
        this.meterRegistry = meterRegistry;
        this.asyncPersistService = asyncPersistService;
        this.matchingEngine = matchingEngine;
        this.redisTemplate = redisTemplate;
        this.shardingMatchingExecutor = shardingMatchingExecutor;
    }

    /**
     * 订单处理全流程：被动撮合专用
     */
    public BaseResponse processOrder(String orderJson) {
        Order order = null;
        String clOrderId = null;
        String processingKey = null;

        try {
            // JSON解析
            order = JsonUtils.fromJson(orderJson, Order.class);
            order.setOriginalQty(order.getQty());
            if (order.getCreateTime() == null) {
                order.setCreateTime(LocalDateTime.now());
            }
            if (order.getVersion() == null) {
                order.setVersion(0);
            }
            clOrderId = order.getClOrderId();
            processingKey = RedisConstant.ORDER_PROCESSING_PREFIX + clOrderId;

            // 布隆过滤器极速拦截
            if (orderIdempotentService.mightExist(clOrderId)) {
                log.info("订单[{}]触发布隆过滤器预警，进入严格校验通道", clOrderId);
                // 只有布隆说“可能存在”时，才去查DB
                return handlePotentialDuplicate(order, processingKey);
            }

            // 基础字段非空校验
            if (clOrderId == null || clOrderId.isEmpty()) {
                log.error("订单[空]clOrderId为空，参数非法");
                orderFailedCounter.increment();
                return buildRejectResponse(order, ErrorCodeEnum.PARAM_NULL);
            }

            try {
                // Key 不存在，正常流程
                Boolean isAbsent = redisTemplate.opsForValue()
                        .setIfAbsent(processingKey, RedisConstant.STATUS_PROCESSING,
                                RedisConstant.PROCESSING_KEY_TTL_MINUTES, TimeUnit.MINUTES);

                if (Boolean.FALSE.equals(isAbsent)) {
                    // 并发场景：刚刚判断完没有，就被别人加锁了
                    log.info("订单[{}]并发冲突，加锁失败", clOrderId);
                    orderRejectCounter.increment();
                    return buildRejectResponse(order, ErrorCodeEnum.ORDER_EXISTED);
                }
            } catch (Exception e) {
                // Redis 挂了的降级策略
                log.error("订单[{}]Redis不可用，系统繁忙", clOrderId, e);
                orderFailedCounter.increment();
                return buildRejectResponse(order, ErrorCodeEnum.REDIS_UNAVAILABLE); // 建议新增一个错误码
            }

            orderTotalCounter.increment();
            final Order finalOrder = order;
            final String finalProcessingKey = processingKey;

            // 不再使用全局的 matchingExecutor，而是根据股票ID获取特定的分片执行器->实现了根据股票ID并发撮合
            Executor targetedExecutor = shardingMatchingExecutor.getExecutor(finalOrder.getSecurityId());
            return CompletableFuture.supplyAsync(() -> {
                return processOrderInMemory(finalOrder, finalProcessingKey);
            }, targetedExecutor).join();

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
     * 内存撮合流程
     */
    public BaseResponse processOrderInMemory(Order order, String processingKey) {
        String clOrderId = order.getClOrderId();
        try {
            // 1. 基础校验
            order.setStatus(OrderStatusEnum.PROCESSING);
            List<ErrorCodeEnum> validateErrors = orderValidator.validate(order);
            if (!validateErrors.isEmpty()) {
                order.setStatus(OrderStatusEnum.REJECTED);
                log.error("订单[{}]基础校验出错：{}", clOrderId, validateErrors.get(0).getDesc());
                return rejectOrderPreInsert(order, validateErrors.get(0), processingKey);
            }

            // 2.风控检查提前到 Insert 之前
            ErrorCodeEnum riskError = selfTradeChecker.checkAndUpdateCache(order);
            if (riskError != null) {
                // 风控拦截，此时还未入库，直接返回拒绝
                log.warn("订单[{}]风控拦截，不执行入库", clOrderId);
                order.setStatus(riskError == ErrorCodeEnum.SELF_TRADE ? OrderStatusEnum.RISK_REJECT : OrderStatusEnum.REJECTED);
                return rejectOrderPreInsert(order, riskError, processingKey);
            }

            // 3. 直接调用撮合引擎进行纯内存计算
            order.setStatus(OrderStatusEnum.MATCHING);
            MatchingResult matchingResult = matchingEngine.match(order); // 这里不再阻塞在数据库IO
            Order matchedOrder = matchingResult.getMatchedOrder();
            List<MatchingResult.MatchCounterDetail> matchDetails = matchingResult.getMatchDetails();

            // 4. 兜底确认状态 (逻辑从 Helper 移到这里，只为返回结果)
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

            // 5. 构建响应，不等待数据库
            BaseResponse response = buildTradeResponse(matchedOrder, trades, counterStatus);
            orderSuccessCounter.increment();
            orderIdempotentService.add(clOrderId);

            // 6. 异步线程更新数据库
            asyncPersistService.persistOrderAndTrades(matchedOrder, counterOrders, trades, processingKey);

            log.info("订单[{}]内存撮合完成，极速返回", clOrderId);
            return response;

        } catch (Exception e) {
            log.error("订单[{}]内存处理异常", clOrderId, e);
            orderFailedCounter.increment();
            deleteProcessingKey(processingKey);
            // 注意：这里如果是在 insert 之后报错，数据库里会有一条 PROCESSING 状态的脏数据
            // 需要在 OrderRecoveryService 里增加对 PROCESSING 状态超时订单的处理逻辑
            ErrorCodeEnum errorCode = ErrorCodeEnum.matchDbError(e);
            return buildRejectResponse(order, errorCode);
        }
    }

    private BaseResponse rejectOrderPreInsert(Order order, ErrorCodeEnum error, String processingKey) {
        // 注意：这里不调用 orderMapper.updateById
        orderRejectCounter.increment();
        log.warn("订单[{}]拒绝（未入库）：{}", order.getClOrderId(), error.getDesc());
        deleteProcessingKey(processingKey);
        return buildRejectResponse(order, error);
    }

    /**
     * 处理布隆过滤器认为“可能重复”的请求
     */
    private BaseResponse handlePotentialDuplicate(Order order, String processingKey) {
        String clOrderId = order.getClOrderId();
        log.info("订单[{}]进入严格校验通道（布隆误判排查）", clOrderId);

        try {
            // 1. 先查 Redis，防止是正在处理中的请求;再查OrderBook防止Redis过期。
            String redisVal = redisTemplate.opsForValue().get(processingKey);
            if (redisVal != null) {
                Order orderInBook = matchingEngine.getOrderBook().findOrderByClOrderId(order.getClOrderId());
                if (orderInBook!= null) {
                    log.info("订单[{}]正在处理中(Redis严格校验)", clOrderId);
                    orderRejectCounter.increment();
                    return buildRejectResponse(order, ErrorCodeEnum.ORDER_EXISTED);
                }
            }

            // 2. 再查 DB (唯一的一次查库)
            if (orderMapper.existsByClOrderId(clOrderId)) {
                log.info("订单[{}]确认在DB中存在，拒绝", clOrderId);
                orderRejectCounter.increment();
                return buildRejectResponse(order, ErrorCodeEnum.ORDER_EXISTED);
            }

            // ========== 核心修改点 ==========
            // 3. 既不在 Redis，也不在 DB：说明是布隆误判！
            log.info("订单[{}]布隆误判，DB确认不存在，放行继续处理", clOrderId);

            // 4. 既然是干净的，我们手动执行一遍正常的加锁流程（分布式锁，失败说明已存在）
            Boolean isAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(processingKey, RedisConstant.STATUS_PROCESSING,
                            RedisConstant.PROCESSING_KEY_TTL_MINUTES, TimeUnit.MINUTES);

            if (Boolean.FALSE.equals(isAbsent)) {
                // 就在我们查 DB 的间隙，被别人抢先加锁了
                log.info("订单[{}]并发加锁失败", clOrderId);
                orderRejectCounter.increment();
                return buildRejectResponse(order, ErrorCodeEnum.ORDER_EXISTED);
            }

            // 5. 加锁成功，回到主流程！
            // 注意：这里需要把后续的撮合逻辑抽取成一个私有方法 doProcessOrder(order)
            // 为了避免代码重复，建议重构
            return doProcessOrder(order, processingKey);

        } catch (Exception e) {
            log.error("订单[{}]严格校验通道异常", clOrderId, e);
            return handleRedisDown(order);
        }
    }

    private BaseResponse doProcessOrder(Order order, String processingKey) {
        // 把原本 processOrder 里 processOrderInMemory 之后的逻辑都放这里
        // 或者直接调用 processOrderInMemory
        try {
            orderTotalCounter.increment();
            BaseResponse response = processOrderInMemory(order, processingKey);

            // 记得这里也要加入布隆过滤器
            orderIdempotentService.add(order.getClOrderId());

            return response;
        } catch (Exception e) {
            // 异常处理
            deleteProcessingKey(processingKey);
            throw e;
        }
    }

    private BaseResponse handleRedisDown(Order order) {
        log.error("Redis不可用，且布隆无记录或校验失败", order.getClOrderId());
        orderFailedCounter.increment();
        return buildRejectResponse(order, ErrorCodeEnum.SYSTEM_BUSY);
    }

    /**
     * 安全删除Redis Key
     */
    private void deleteProcessingKey(String processingKey) {
        if (processingKey != null) {
            try {
                redisTemplate.delete(processingKey);
                log.info("已释放Redis处理锁：{}", processingKey);
            } catch (Exception e) {
                log.warn("释放Redis处理锁失败：{}", processingKey, e);
            }
        }
    }

    /*构建单笔Trade对象*/
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

    /*构建单笔TradeResponse*/
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

    /*构建拒绝响应*/
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