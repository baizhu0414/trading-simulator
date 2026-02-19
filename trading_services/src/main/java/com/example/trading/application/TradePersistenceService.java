package com.example.trading.application;

import com.example.trading.application.response.BaseResponse;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.engine.MatchingEngine;
import com.example.trading.domain.engine.result.MatchingResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立的成交/订单持久化服务（用于解耦 ExchangeService 和 MatchingEngine）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradePersistenceService {
    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;
    private final SelfTradeChecker selfTradeChecker;

    /**
     * 更新我方订单并清理缓存（selfTradeChecker）
     * 主动撮合（recovery）
     */
    public void updateOrder(Order order) {
        // 关键：乐观锁，先查询数据库中对手单的最新version
        Order latestOrder = orderMapper.selectByClOrderId(order.getClOrderId()).orElse(null);
        if (latestOrder != null) {
            order.setVersion(latestOrder.getVersion()); // 同步最新version
        }

        int updateCount = orderMapper.updateById(order);
        if (updateCount == 0) throw new RuntimeException("我方订单乐观锁冲突");

        log.info("我方订单[{}]更新成功：状态={}, 剩余数量={}",
                order.getClOrderId(), order.getStatus(), order.getQty());

        // 完全成交时清理风控缓存
        if (order.getStatus() == OrderStatusEnum.FULL_FILLED) {
            selfTradeChecker.removeCache(order.getShareholderId(), order.getSecurityId());
        }
    }

    /**
     * 新增：插入成交记录（每次成交都执行，兼容整手）
     * 主动撮合（recovery）
     */
    public void insertTradeRecord(Order orderA, Order orderB, int tradeQty, BigDecimal tradePrice) {
        if (orderB == null || tradeQty == 0) return;
        try {
            // 构建成交记录
            Trade trade = new Trade();
            String execId = TradeResponseHelper.generateExecId();
            trade.setExecId(execId); // 生成12位execId
            trade.setExecQty(tradeQty);
            trade.setExecPrice(tradePrice);
            trade.setTradeTime(LocalDateTime.now());
            // 幂等校验：execId全局唯一，已存在则直接返回
            if (tradeMapper.existsByExecId(execId) > 0) {
                log.warn("成交记录已存在，幂等校验通过，execId={}", execId);
                return;
            }
            // 区分买卖方（确保buyClOrderId和sellClOrderId正确）
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
            trade.setMarket(orderA.getMarket());
            trade.setSecurityId(orderA.getSecurityId());

            // 插入数据库
            int insertCount = tradeMapper.insert(trade);
            if (insertCount <= 0) {
                log.error("成交记录插入失败：execId={}，成交数量={}", trade.getExecId(), trade.getExecQty());
                throw new RuntimeException("成交记录插入失败");
            }
            log.info("成交记录插入成功：execId={}，买方订单={}，卖方订单={}，成交数量={}，成交价格={}",
                    trade.getExecId(), trade.getBuyClOrderId(), trade.getSellClOrderId(),
                    trade.getExecQty(), trade.getExecPrice());
        } catch (Exception e) {
            log.error("插入成交记录失败（订单A：{}，订单B：{}）", orderA.getClOrderId(), orderB.getClOrderId(), e);
            throw new RuntimeException("撮合成交记录落地失败", e); // 抛出异常触发事务回滚
        }
    }
}