package com.example.trading.application;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.engine.MatchingEngine.RecoveryMatchResult;
import com.example.trading.domain.model.Order;
import com.example.trading.domain.model.Trade;
import com.example.trading.domain.risk.SelfTradeChecker;
import com.example.trading.mapper.OrderMapper;
import com.example.trading.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 持久化任务落库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistCoreService {

    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;
    private final SqlSessionFactory sqlSessionFactory;
    private final SelfTradeChecker selfTradeChecker;

    @Transactional(rollbackFor = Exception.class)
    public void doPersistRecoveryResults(List<RecoveryMatchResult> allRecoveryResults) {
        if (allRecoveryResults== null || allRecoveryResults.isEmpty()) {
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
            orderMapper.batchUpdateTradeOrderByOrderId(allOrders); // 没成交则不会有订单变化
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
                updateCount = orderMapper.updateTradedOrderByOrderId(canceledOrder);
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
                orderMapper.updateTradedOrderByOrderId(canceledOrder);
            }
        }

        canceledOrder.setVersion(canceledOrder.getVersion() + 1);
        selfTradeChecker.removeCache(canceledOrder.getShareholderId(), canceledOrder.getSecurityId());
        log.info("[事务] 撤单[{}] 持久化完成", clOrderId);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void batchInsertOrders(List<Order> orders) {
        if (orders.isEmpty()) {
            log.info("[批量事务] 待插入的订单为空，无需处理");
            return;
        }
        log.info("[批量事务] 开始批量新增订单{}条", orders.size());

        // 修正：Batch 模式下关闭自增键赋值，仅执行批量插入
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            OrderMapper orderMapper = sqlSession.getMapper(OrderMapper.class);
            orderMapper.batchInsert(orders);
            // 修正：先 flushStatements 再 commit，避免 Batch 缓存未刷新
            sqlSession.flushStatements();
            sqlSession.commit();
            orders.forEach(o -> o.setVersion(o.getVersion() + 1));
            log.info("[批量事务] 批量新增订单{}条完成", orders.size());
        } catch (Exception e) {
            log.error("[批量事务] 批量新增订单失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 批量持久化撤单（真正的批量SQL）
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchPersistCancelOrders(List<Order> canceledOrders) {
        if (canceledOrders.isEmpty()) {
            return;
        }

        String batchLogPrefix = "[批量事务] 撤单批量处理-" + System.currentTimeMillis();
        log.info("{} 开始批量持久化撤单{}条，订单ID：{}",
                batchLogPrefix,
                canceledOrders.size(),
                canceledOrders.stream().map(Order::getClOrderId).collect(Collectors.joining(",")));

        // 1. 初始化版本号 + 确保状态是CANCELED
        canceledOrders.forEach(o -> {
            if (o.getVersion() == null) {
                o.setVersion(0);
            }
            // 强制设置撤单状态，避免内存状态错误
            o.setStatus(OrderStatusEnum.CANCELED);
        });

        try {
            // 2. 用无乐观锁的撤单更新方法（核心修复）
            int updateCount = orderMapper.batchUpdateCancelOrderByOrderId(canceledOrders);
            log.info("{} 批量更新撤单{}条，成功更新{}条", batchLogPrefix, canceledOrders.size(), updateCount);

            // 批量清理风控缓存 + 更新内存版本号
            canceledOrders.forEach(o -> {
                selfTradeChecker.removeCache(o.getShareholderId(), o.getSecurityId());
                o.setVersion(o.getVersion() + 1);
            });

            log.info("{} 撤单批量持久化完成，总计处理{}条（更新{}条）",
                    batchLogPrefix,
                    canceledOrders.size(),
                    updateCount);
        } catch (Exception e) {
            log.error("{} 撤单批量持久化失败", batchLogPrefix, e);
            throw e; // 抛出异常触发重试，确保数据不丢失
        }
    }

    /**
     * 批量更新成交后的订单（仅更新status、qty、version三个字段）
     * 对应counterOrders的批量更新，保证只修改核心字段，避免冗余更新
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void batchUpdateTradedOrder(List<Order> tradedOrders) {
        if (tradedOrders.isEmpty()) {
            log.info("[批量事务] 待更新的成交订单为空，无需处理");
            return;
        }
        log.info("[批量事务] 开始批量更新成交订单{}条（仅status/qty/version）", tradedOrders.size());

        // 1. 前置处理：按clOrderId排序避免死锁，初始化version
        List<Order> sortedOrders = tradedOrders.stream()
                .sorted(Comparator.comparing(Order::getClOrderId))
                .collect(Collectors.toList());
        sortedOrders.forEach(o -> {
            if (o.getVersion() == null) {
                o.setVersion(0);
            }
        });

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            OrderMapper orderMapper = sqlSession.getMapper(OrderMapper.class);
            // 核心：调用仅更新status/qty/version的批量方法（需在OrderMapper中新增该方法）
            orderMapper.batchUpdateStatusQtyVersionById(sortedOrders);
            sqlSession.commit();

            // 更新内存版本号
            sortedOrders.forEach(o -> o.setVersion(o.getVersion() + 1));
            log.info("[批量事务] 批量更新成交订单{}条完成", sortedOrders.size());
        } catch (Exception e) {
            log.error("[批量事务] 批量更新成交订单失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 批量插入交易记录（封装tradeMapper.batchInsert，统一入口）
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void batchInsertTrades(List<Trade> trades) {
        if (trades.isEmpty()) {
            log.info("[批量事务] 待插入的交易记录为空，无需处理");
            return;
        }
        log.info("[批量事务] 开始批量插入交易记录{}条", trades.size());

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            TradeMapper tradeMapper = sqlSession.getMapper(TradeMapper.class);
            tradeMapper.batchInsert(trades);
            sqlSession.commit();
            log.info("[批量事务] 批量插入交易记录{}条完成", trades.size());
        } catch (Exception e) {
            log.error("[批量事务] 批量插入交易记录失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 单条插入新订单（对应matchedOrder，仅INSERT操作）
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void doPersistOrderInsert(Order newOrder) {
        if (newOrder == null) {
            log.warn("[单条事务] 待插入的新订单为空，无需处理");
            return;
        }
        String clOrderId = newOrder.getClOrderId();
        log.info("[单条事务] 开始插入新订单[{}]", clOrderId);

        // 前置处理：初始化version
        if (newOrder.getVersion() == null) {
            newOrder.setVersion(0);
        }

        try {
            orderMapper.insert(newOrder);
            // 更新内存版本号
            newOrder.setVersion(newOrder.getVersion() + 1);
            log.info("[单条事务] 插入新订单[{}]完成", clOrderId);
        } catch (DuplicateKeyException e) {
            // 兼容：若插入冲突（订单已存在），执行强制更新（兜底逻辑）
            log.warn("[单条事务] 新订单[{}]插入冲突，执行强制更新", clOrderId);
            orderMapper.updateTradedOrderByOrderId(newOrder);
            newOrder.setVersion(newOrder.getVersion() + 1);
        } catch (Exception e) {
            log.error("[单条事务] 插入新订单[{}]失败", clOrderId, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 单条更新订单的status、qty、version（仅这三个字段）
     * 对应counterOrders的单条重试场景
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void doUpdateTradedOrder(Order order) {
        if (order == null) {
            log.warn("[单条事务] 待更新的订单为空，无需处理");
            return;
        }
        String clOrderId = order.getClOrderId();
        log.info("[单条事务] 开始更新订单[{}]的status/qty/version", clOrderId);

        // 前置处理：初始化version
        if (order.getVersion() == null) {
            order.setVersion(0);
        }

        try {
            // 核心：调用仅更新status/qty/version的单条方法（需在OrderMapper中新增该方法）
            int updateCount = orderMapper.updateStatusQtyVersionById(order);
            if (updateCount == 0) {
                log.warn("[单条事务] 订单[{}]更新未命中（可能未入库），跳过", clOrderId);
                return;
            }
            // 更新内存版本号
            order.setVersion(order.getVersion() + 1);
            log.info("[单条事务] 更新订单[{}]的status/qty/version完成", clOrderId);
        } catch (Exception e) {
            log.error("[单条事务] 更新订单[{}]的status/qty/version失败", clOrderId, e);
            throw new RuntimeException(e);
        }
    }
}