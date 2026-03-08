package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.application.response.CancelConfirmResponse;
import com.example.trading.application.response.CancelRejectResponse;
import com.example.trading.common.RedisConstant;
import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.ResponseCodeEnum;
import com.example.trading.config.ShardingMatchingExecutor;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.OrderBook;
import com.example.trading.domain.model.CancelOrder;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.validation.CancelValidator;
import com.example.trading.gate.ThreadPoolStatusMonitor;
import com.example.trading.util.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 撤单核心服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelService {
    private final CancelValidator cancelValidator;
    private final MatchingEngine matchingEngine;
    private final OrderBook orderBook;
    private final AsyncPersistService asyncPersistService;
    private final ShardingMatchingExecutor shardingMatchingExecutor; // 注入分片执行器
    private final ThreadPoolStatusMonitor threadPoolStatusMonitor;
    private final MeterRegistry meterRegistry;

    /* 撤单请求总次数计数器 */
    private Counter cancelTotalCounter;
    /* 撤单成功次数计数器 */
    private Counter cancelSuccessCounter;
    /* 撤单被拒绝次数计数器 */
    private Counter cancelRejectCounter;
    /* 撤单处理失败次数计数器 */
    private Counter cancelFailedCounter;

    @PostConstruct
    public void initMetrics() {
        cancelTotalCounter = meterRegistry.counter("trading.cancel.total");
        cancelSuccessCounter = meterRegistry.counter("trading.cancel.success");
        cancelRejectCounter = meterRegistry.counter("trading.cancel.reject");
        cancelFailedCounter = meterRegistry.counter("trading.cancel.failed");
    }

    /**
     * 撤单处理入口
     */
    public BaseResponse processCancel(String cancelJson) {
        CancelOrder cancelOrder = null;
        String origClOrderId = null;

        try {
            cancelOrder = JsonUtils.fromJson(cancelJson, CancelOrder.class);
            // !!!前置校验：任意线程池满则返回繁忙!!!
            if (threadPoolStatusMonitor.isAnyThreadPoolFull()) {
                return CancelRejectResponse.build(ErrorCodeEnum.SYSTEM_BUSY);
            }
            origClOrderId = cancelOrder.getOrigClOrderId();

            cancelTotalCounter.increment();
            return processCancelInMemory(cancelOrder);

        } catch (IllegalArgumentException e) {
            log.error("撤单请求参数格式错误：{}", e.getMessage());
            cancelFailedCounter.increment();
            return CancelRejectResponse.build(ErrorCodeEnum.PARAM_FORMAT_ERROR);
        } catch (Exception e) {
            log.error("撤单请求[原订单：{}]处理全局异常", origClOrderId, e);
            cancelFailedCounter.increment();
            ErrorCodeEnum errorCode = ErrorCodeEnum.matchDbError(e);
            return CancelRejectResponse.build(origClOrderId, origClOrderId, errorCode);
        }
    }

    /**
     * 处理撤单核心逻辑
     */
    public BaseResponse processCancelInMemory(CancelOrder cancelOrder) {
        String origClOrderId = cancelOrder.getOrigClOrderId();
        String processingKey = RedisConstant.ORDER_PROCESSING_PREFIX + origClOrderId;

        try {
            // 基础格式校验
            List<ErrorCodeEnum> validateErrors = cancelValidator.validate(cancelOrder);
            if (!validateErrors.isEmpty()) {
                log.warn("撤单请求[原订单：{}]基础校验失败：{}", origClOrderId, validateErrors.get(0).getDesc());
                cancelRejectCounter.increment();
                return CancelRejectResponse.build(origClOrderId, origClOrderId, validateErrors.get(0));
            }

            String securityId = cancelOrder.getSecurityId();

            // 将撤单逻辑封装，提交给分片线程
            return shardingMatchingExecutor.submitAndWait(securityId, () -> {
                // 查询原订单
                Order originOrder = orderBook.findOrderByClOrderId(origClOrderId);
                if (originOrder == null) {
                    log.info("撤单请求失败：原订单[{}]不在订单簿中，无效订单号", origClOrderId);
                    cancelRejectCounter.increment();
                    return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.ORDER_NOT_IN_ORDER_BOOK);
                }

                // 业务校验：撤单信息与原订单一致性校验
                if (!isCancelInfoMatch(cancelOrder, originOrder)) {
                    log.warn("撤单请求失败：撤单信息与原订单[{}]不匹配", origClOrderId);
                    cancelRejectCounter.increment();
                    return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.CANCEL_INFO_MISMATCH);
                }

                // 业务校验：订单状态是否可撤销
                if (!originOrder.getStatus().isCancelable()) {
                    log.warn("撤单请求失败：原订单[{}]状态[{}]不可撤销", origClOrderId, originOrder.getStatus().getDesc());
                    cancelRejectCounter.increment();
                    return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.ORDER_NOT_CANCELABLE);
                }

                // 调用撮合引擎执行原子撤单
                boolean removeSuccess = matchingEngine.handleCancelOrder(originOrder);
                if (!removeSuccess) {
                    log.error("撤单请求失败：原订单[{}]从订单簿全量移除失败", origClOrderId);
                    cancelFailedCounter.increment();
                    return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.CANCEL_PROCESS_FAILED);
                }

                // 全量更新原订单字段，qty代表取消订单数。
                originOrder.setStatus(OrderStatusEnum.CANCELED);

                // 先告诉用户撤单成功了
                cancelSuccessCounter.increment();
                BaseResponse response = buildCancelConfirmResponse(cancelOrder, originOrder);
                // 后台慢慢更新数据库
                asyncPersistService.persistCancel(originOrder, processingKey);

                log.info("撤单[{}]内存处理完成，极速返回", origClOrderId);
                return response;
            });
        } catch (Exception e) {
            log.error("撤单请求[原订单：{}]事务处理失败", origClOrderId, e);
            cancelFailedCounter.increment();
            throw e;
        }
    }

    /**
     * 构建撤单成功确认响应
     */
    private CancelConfirmResponse buildCancelConfirmResponse(CancelOrder cancelOrder, Order originOrder) {
        return CancelConfirmResponse.builder()
                .clOrderId(originOrder.getClOrderId())
                .origClOrderId(cancelOrder.getOrigClOrderId())
                .market(originOrder.getMarket())
                .securityId(originOrder.getSecurityId())
                .shareholderId(originOrder.getShareholderId())
                .side(originOrder.getSide().getCode())
                .qty(originOrder.getQty()) // 剩余数量
                .cumQty(originOrder.getOriginalQty() - originOrder.getQty())
                .price(originOrder.getPrice())
                .orderStatus(OrderStatusEnum.CANCELED.getDesc())
                .code(ResponseCodeEnum.CANCEL_SUCCESS.getCode())
                .msg(ResponseCodeEnum.CANCEL_SUCCESS.getDesc())
                .build();
    }
    /**
     * 校验撤单信息与原订单是否匹配
     */
    private boolean isCancelInfoMatch(CancelOrder cancelOrder, Order originOrder) {
        return cancelOrder.getMarket().equals(originOrder.getMarket())
                && cancelOrder.getSecurityId().equals(originOrder.getSecurityId())
                && cancelOrder.getShareholderId().equals(originOrder.getShareholderId())
                && cancelOrder.getSide() == originOrder.getSide();
    }

}