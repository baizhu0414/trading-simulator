package com.example.trading.application;

import com.example.trading.domain.engine.MatchingEngine.RecoveryMatchResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 持久化任务落库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistCoreService {

    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;
    private final SelfTradeChecker selfTradeChecker;

    @Transactional(rollbackFor = Exception.class)
    public void doPersistOrderAndTrades(Order matchedOrder, List<Order> counterOrders, List<Trade> trades) {
        String clOrderId = matchedOrder.getClOrderId();
        log.info("[事务] 开始持久化订单[{}]", clOrderId);

        if (!trades.isEmpty()) {
            tradeMapper.batchInsert(trades);
        }

        List<Order> allOrders = new ArrayList<>();
        allOrders.add(matchedOrder);
        allOrders.addAll(counterOrders);

        allOrders.forEach(o -> {
            if (o.getVersion() == null) o.setVersion(0);
        });

        orderMapper.batchUpsert(allOrders);
        log.info("[事务] 批量 Upsert {} 个订单完成", allOrders.size());

        allOrders.forEach(o -> o.setVersion(o.getVersion() + 1)); // 先不删除，防止同一个人对同一股票有多个同方向订单
        log.info("[事务] 订单[{}] 持久化完成", clOrderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void doPersistRecoveryResults(List<RecoveryMatchResult> allRecoveryResults) {
        if (allRecoveryResults.isEmpty()) {
            return;
        }

        String batchId = "RECOVER_" + System.currentTimeMillis();
        log.info("[事务] 开始恢复持久化，批次[{}]，共{}笔", batchId, allRecoveryResults.size());

        List<Trade> allTrades = new ArrayList<>();
        List<Order> allOrders = new ArrayList<>();

        for (RecoveryMatchResult result : allRecoveryResults) {
            if (result.getTrade() != null) allTrades.add(result.getTrade());
            if (result.getBuyOrder() != null) allOrders.add(result.getBuyOrder());
            if (result.getSellOrder() != null) allOrders.add(result.getSellOrder());
        }

        if (!allTrades.isEmpty()) {
            tradeMapper.batchInsert(allTrades);
            orderMapper.batchUpdateById(allOrders); // 没成交则不会有订单变化
            for (Order order : allOrders) {
                if (order.getStatus().isFinalStatus()) { // 新出现的
                    selfTradeChecker.removeCache(order.getShareholderId(), order.getSecurityId());
                }
                order.setVersion(order.getVersion() + 1);
            }
        }
        log.info("[事务] 恢复持久化批次[{}] 完成", batchId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void doPersistCancel(Order canceledOrder) {
        String clOrderId = canceledOrder.getClOrderId();
        log.info("[事务] 开始持久化撤单[{}]", clOrderId);

        int updateCount = 0;
        try {
            if (canceledOrder.getId() != null && canceledOrder.getVersion() != null) {
                updateCount = orderMapper.updateById(canceledOrder);
            }
        } catch (Exception e) {
            log.warn("[事务] 撤单更新尝试失败（可能订单未入库），准备 Upsert: {}", e.getMessage());
        }

        if (updateCount == 0) {
            log.info("[事务] 订单[{}]未在DB中找到或版本过期，执行 Upsert (Insert CANCELED)", clOrderId);

            if (canceledOrder.getVersion() == null) {
                canceledOrder.setVersion(0);
            }

            try {
                orderMapper.insert(canceledOrder);
            } catch (DuplicateKeyException dke) {
                log.warn("[事务] 订单[{}]插入冲突（唯一索引），说明订单刚入库，执行强制 Update", clOrderId);
                orderMapper.forceUpdateStatusByClOrderId(canceledOrder);
            }
        }

        canceledOrder.setVersion(canceledOrder.getVersion() + 1);
        selfTradeChecker.removeCache(canceledOrder.getShareholderId(), canceledOrder.getSecurityId());
        log.info("[事务] 撤单[{}] 持久化完成", clOrderId);
    }
}