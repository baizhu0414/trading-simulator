package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.application.response.OrderConfirmResponse;
import com.example.trading.application.response.TradeResponse;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.ResponseCodeEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.result.MatchingResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 独立的交易对象构建辅助类（纯逻辑，无依赖，打破循环）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeResponseHelper {

    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;
    private final MatchingEngine matchingEngine;
    private final SelfTradeChecker selfTradeChecker;

    /**
     * 核心事务方法（从 ExchangeService 抽离出来）
     * 现在通过独立 Service 调用，@Transactional 生效
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse executeOrderTransaction(Order order) {
        String clOrderId = order.getClOrderId();
        try {
            // 撮合（获取包含多笔匹配订单的MatchingResult）
            order.setStatus(OrderStatusEnum.MATCHING);
            MatchingResult matchingResult = matchingEngine.match(order);
            Order matchedOrder = matchingResult.getMatchedOrder();
            List<MatchingResult.MatchCounterDetail> matchDetails = matchingResult.getMatchDetails();

            // 兜底确认订单状态
            if (matchDetails.isEmpty()) {
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

                Trade trade = buildTrade(matchedOrder, detail.getCounterPartyOrder(),
                        detail.getExecQty(), detail.getExecPrice());
                trades.add(trade);

                counterStatus.add(counterOrder.getStatus());
            }

            // 批量插入成交记录
            if (!trades.isEmpty()) {
                tradeMapper.batchInsert(trades);
                log.info("订单[{}]批量插入{}笔成交记录", clOrderId, trades.size());
            }

            // ========== 测试回滚：临时保留 ==========
//            if(!trades.isEmpty()) {
//                throw new RuntimeException("模拟异常");
//            }
            // =========================================

            // 批量更新订单（当前订单+所有对手方订单）
            orderMapper.batchUpdateById(allOrdersToUpdate);
            log.info("订单[{}]批量更新{}笔订单", clOrderId, allOrdersToUpdate.size());

            // 清理风控缓存（完全成交的订单）
            for (Order o : allOrdersToUpdate) {
                if (o.getStatus() == OrderStatusEnum.FULL_FILLED) {
                    selfTradeChecker.removeCache(o.getShareholderId(), o.getSecurityId());
                }
                o.setVersion(o.getVersion() + 1);
            }

            // 构建返回结果
            return buildTradeResponse(matchedOrder, trades, counterStatus);

        } catch (Exception e) {
            log.error("订单[{}]事务失败", clOrderId, e);
            throw e; // 重新抛出异常，触发回滚
        }
    }

    /**
     * 构建单笔Trade对象（从 ExchangeService 抽离）
     */
    public Trade buildTrade(Order orderA, Order orderB, int tradeQty, BigDecimal tradePrice) {
        Trade trade = new Trade();
        trade.setExecId(generateExecId());
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

    /**
     * 构建单笔TradeResponse（从 ExchangeService 抽离）
     */
    public BaseResponse buildTradeResponse(Order matchedOrder, List<Trade> trades, List<OrderStatusEnum> counterStatus) {
        if (trades.isEmpty()) {
            return OrderConfirmResponse.builder()
                    .clOrderId(matchedOrder.getClOrderId())
                    .market(matchedOrder.getMarket())
                    .shareholderId(matchedOrder.getShareholderId())
                    .side(matchedOrder.getSide().getCode())
                    .qty(matchedOrder.getQty())
                    .price(matchedOrder.getPrice())
                    .orderStatus(matchedOrder.getStatus().getDesc())
                    .code(ResponseCodeEnum.ORDER_CONFIRMED.getCode())
                    .msg(ResponseCodeEnum.ORDER_CONFIRMED.getDesc())
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

        for (int i = 0; i < trades.size() && i < counterStatus.size(); i++) {
            Trade t = trades.get(i);
            tradeResponse.addTradeResponse(t.getExecId(), t.getExecQty(), t.getExecPrice(), t.getTradeTime(), counterStatus.get(i).getDesc());
        }

        return tradeResponse;
    }

    /**
     * 生成12位execId（从 TradePersistenceService 抽离）
     */
    public static String generateExecId() {
        String timestamp = String.valueOf(System.currentTimeMillis()).substring(4, 12);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return timestamp + random;
    }
}