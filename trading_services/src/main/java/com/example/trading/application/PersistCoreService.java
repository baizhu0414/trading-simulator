package com.example.trading.application;

import com.example.trading.domain.engine.MatchingEngine.RecoveryMatchResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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

        orderMapper.batchUpdateById(allOrders);
        log.info("[事务] 订单[{}] 持久化完成", clOrderId);

        for (Order o : allOrders) {
            if (o.getStatus().isFinalStatus()) {
                selfTradeChecker.removeCache(o.getShareholderId(), o.getSecurityId());
            }
            o.setVersion(o.getVersion() + 1);
        }
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

        int updateCount = orderMapper.updateById(canceledOrder);
        if (updateCount == 0) {
            throw new RuntimeException("撤单失败，乐观锁冲突");
        }

        canceledOrder.setVersion(canceledOrder.getVersion() + 1);
        selfTradeChecker.removeCache(canceledOrder.getShareholderId(), canceledOrder.getSecurityId());
        log.info("[事务] 撤单[{}] 持久化完成", clOrderId);
    }
}