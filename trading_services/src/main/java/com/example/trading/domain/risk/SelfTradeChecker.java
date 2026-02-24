package com.example.trading.domain.risk;

import com.example.trading.common.enums.ErrorCodeEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.infrastructure.network.TcpClient;
import com.example.trading.util.JsonUtils;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 对敲风控检查器（调用外部 Python 风控服务）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfTradeChecker {

    private final TcpClient tcpClient;

    @Value("${risk.host:127.0.0.1}")
    private String riskHost;

    @Value("${risk.port:9002}")
    private int riskPort;

    /**
     * 构建发送给 Python 的请求体结构
     */
    @Data
    @Builder
    public static class RiskCheckRequest {
        private Order incomingOrder;
        private List<Order> existingOrders;
    }

    /**
     * Python 返回的响应结构
     */
    @Data
    public static class RiskCheckResponse {
        private boolean allow;
        private String reason;
    }

    /**
     * 检查是否存在对敲交易
     * 
     * @return 错误码（null则通过）
     */
    public ErrorCodeEnum check(Order order) {
        try {
            // 组装风控请求协议 (protocol/ipc/risk_check_request.schema.json)
            RiskCheckRequest request = RiskCheckRequest.builder()
                    .incomingOrder(order)
                    .existingOrders(Collections.emptyList()) // 暂时不传 OrderBook 的历史订单，由 Python 的内存缓存提供判断
                    .build();

            log.info("向独立风控服务 {}:{} 发送订单{}风控检查", riskHost, riskPort, order.getClOrderId());
            String responseJson = tcpClient.sendRequest(riskHost, riskPort, request);

            RiskCheckResponse response = JsonUtils.fromJson(responseJson, RiskCheckResponse.class);
            if (response != null && response.isAllow()) {
                log.info("订单{}风控检查通过", order.getClOrderId());
                return null; // 允许通过
            } else {
                log.warn("订单{}触发对敲风控：{}", order.getClOrderId(), response != null ? response.getReason() : "无原因");
                return ErrorCodeEnum.SELF_TRADE;
            }
        } catch (Exception e) {
            log.error("调用风控服务失败", e);
            // 工业级实际系统风控连不上应该拒单，但为了测试方便可以考虑报错
            return ErrorCodeEnum.SYSTEM_BUSY;
        }
    }

    /**
     * 订单成交/撤单后，移除风控缓存
     */
    public void removeCache(String shareholderId, String securityId) {
        // 由于风控逻辑已经移至 Python 服务，此处的清理应通过接口告诉 Python 撤单
        // 此重构暂留空，后续实现撤单同步时再完善
        log.info("订单已撤/已成，应当通知 Python 清理股东 {}_{} 的缓存", shareholderId, securityId);
    }
}