package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.application.response.CancelConfirmResponse;
import com.example.trading.application.response.CancelRejectResponse;
import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.ResponseCodeEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.model.CancelOrder;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.validation.CancelValidator;
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

import java.util.List;

/**
 * 撤单核心服务（终极简化版：仅用原订单号，无流水号）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelService {
    private final CancelValidator cancelValidator;
    private final MatchingEngine matchingEngine;
    private final OrderMapper orderMapper;
    // 新增：注入异步持久化服务
    private final AsyncPersistService asyncPersistService;
    private final MeterRegistry meterRegistry;

    // 监控指标（保留）
    private Counter cancelTotalCounter;
    private Counter cancelSuccessCounter;
    private Counter cancelRejectCounter;
    private Counter cancelFailedCounter;

    @PostConstruct
    public void initMetrics() {
        cancelTotalCounter = meterRegistry.counter("trading.cancel.total");
        cancelSuccessCounter = meterRegistry.counter("trading.cancel.success");
        cancelRejectCounter = meterRegistry.counter("trading.cancel.reject");
        cancelFailedCounter = meterRegistry.counter("trading.cancel.failed");
    }

    /**
     * 撤单处理入口（简化版）
     */
    @Timed(value = "trading.cancel.process.time", description = "撤单处理总耗时")
    public BaseResponse processCancel(String cancelJson) {
        CancelOrder cancelOrder = null;
        String origClOrderId = null;

        try {
            cancelOrder = JsonUtils.fromJson(cancelJson, CancelOrder.class);
            origClOrderId = cancelOrder.getOrigClOrderId();

            cancelTotalCounter.increment();
            return processCancelInTransaction(cancelOrder);

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
     * 事务内处理撤单核心逻辑（终极简化版）
     */
//    @Transactional(rollbackFor = Exception.class)
    public BaseResponse processCancelInTransaction(CancelOrder cancelOrder) {
        String origClOrderId = cancelOrder.getOrigClOrderId();

        try {
            // 1. 基础格式校验（含缓存快速拦截）
            List<ErrorCodeEnum> validateErrors = cancelValidator.validate(cancelOrder);
            if (!validateErrors.isEmpty()) {
                log.warn("撤单请求[原订单：{}]基础校验失败：{}", origClOrderId, validateErrors.get(0).getDesc());
                cancelRejectCounter.increment();
                return CancelRejectResponse.build(origClOrderId, origClOrderId, validateErrors.get(0));
            }

            // 2. 查询原订单
            Order originOrder = orderMapper.selectByClOrderId(origClOrderId).orElse(null);
            if (originOrder == null) {
                log.warn("撤单请求失败：原订单[{}]不存在", origClOrderId);
                cancelRejectCounter.increment();
                return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.ORIGIN_ORDER_NOT_EXIST);
            }

            // 3. 业务校验：撤单信息与原订单一致性校验
            if (!isCancelInfoMatch(cancelOrder, originOrder)) {
                log.warn("撤单请求失败：撤单信息与原订单[{}]不匹配", origClOrderId);
                cancelRejectCounter.increment();
                return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.CANCEL_INFO_MISMATCH);
            }

            // 4. 业务校验：订单状态是否可撤销（数据库二次确认）
            if (!originOrder.getStatus().isCancelable()) {
                log.warn("撤单请求失败：原订单[{}]状态[{}]不可撤销", origClOrderId, originOrder.getStatus().getDesc());
                cancelRejectCounter.increment();
                return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.ORDER_NOT_CANCELABLE);
            }

            // 5. 调用撮合引擎执行原子撤单
            boolean removeSuccess = matchingEngine.handleCancelOrder(originOrder);
            if (!removeSuccess) {
                log.error("撤单请求失败：原订单[{}]从订单簿全量移除失败", origClOrderId);
                cancelFailedCounter.increment();
                return CancelRejectResponse.build(origClOrderId, origClOrderId, ErrorCodeEnum.CANCEL_PROCESS_FAILED);
            }

            // 6. 全量更新原订单字段（仅修改 status、version），qty代表取消订单数。
            originOrder.setStatus(OrderStatusEnum.CANCELED); // 仅靠 status 标识已撤销
//            originOrder.setVersion(originOrder.getVersion());

            // 7. 持久化更新订单信息
//            tradePersistenceService.updateOrder(originOrder);

            // 7. 【立即返回】先告诉用户撤单成功了
            cancelValidator.markOrderAsCanceled(origClOrderId);
            cancelSuccessCounter.increment();
            BaseResponse response = buildCancelConfirmResponse(cancelOrder, originOrder);

            // 8. 【异步】后台慢慢更新数据库
            asyncPersistService.persistCancel(originOrder);

            log.info("撤单[{}]内存处理完成，极速返回", origClOrderId);
            return response;

        } catch (Exception e) {
            log.error("撤单请求[原订单：{}]事务处理失败", origClOrderId, e);
            cancelFailedCounter.increment();
            throw e;
        }
    }

    /**
     * 构建撤单成功确认响应（简化版，移除 cumQty 和 canceledQty）
     */
    private CancelConfirmResponse buildCancelConfirmResponse(CancelOrder cancelOrder, Order originOrder) {
        return CancelConfirmResponse.builder()
                .origClOrderId(cancelOrder.getOrigClOrderId())
                .market(originOrder.getMarket())
                .securityId(originOrder.getSecurityId())
                .shareholderId(originOrder.getShareholderId())
                .side(originOrder.getSide().getCode())
                .qty(originOrder.getOriginalQty())
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