//package com.example.trading.application;
//
//import com.example.trading.common.enums.OrderStatusEnum;
//import com.example.trading.common.enums.SideEnum;
//import com.example.trading.domain.model.Order;
//import com.example.trading.domain.model.Trade;
//import com.example.trading.domain.risk.SelfTradeChecker;
//import com.example.trading.mapper.OrderMapper;
//import com.example.trading.mapper.TradeMapper;
//import com.example.trading.util.ExecIdGenUtils;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//
///**
// * 独立的成交/订单持久化服务（用于解耦 ExchangeService 和 MatchingEngine）
// * 由于持久化功能抽离成单独线程池服务，此持久化类弃用。
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//@Deprecated
//public class TradePersistenceService {
//    private final OrderMapper orderMapper;
//    private final TradeMapper tradeMapper;
//    private final SelfTradeChecker selfTradeChecker;
//
//    /**
//     * 更新我方订单并清理缓存（selfTradeChecker）
//     * 主动撮合（recovery）
//     */
//    public void updateOrder(Order order) {
//        // 1. 【核心修改】乐观锁的正确用法：直接用传入的 version 更新，数据库自己加 1
//        int updateCount = orderMapper.updateById(order);
//        // 2. 乐观锁失败处理
//        if (updateCount == 0) {
//            throw new RuntimeException("订单[" + order.getClOrderId() + "]乐观锁冲突，更新失败");
//        }
//        // 3. 【关键】同步内存对象的 version（如果后续还会用这个对象）
//        // 数据库里已经是 version + 1 了，内存对象也要跟上
//        order.setVersion(order.getVersion() + 1);
//        log.info("订单[{}]更新成功：状态={}, 剩余数量={}, 版本号={}",
//                order.getClOrderId(), order.getStatus(), order.getQty(), order.getVersion());
//        // 5. 风控缓存清理（补充 CANCELED）
//        if (order.getStatus() == OrderStatusEnum.FULL_FILLED
//                || order.getStatus() == OrderStatusEnum.CANCELED) {
//            selfTradeChecker.removeCache(order.getShareholderId(), order.getSecurityId());
//        }
//    }
//
//    /**
//     * 新增：插入成交记录（每次成交都执行，兼容整手）
//     * 主动撮合（recovery）
//     */
//    public void insertTradeRecord(Order orderA, Order orderB, int tradeQty, BigDecimal tradePrice) {
//        if (orderB == null || tradeQty == 0) return;
//        try {
//            // 构建成交记录
//            Trade trade = new Trade();
//            String execId = ExecIdGenUtils.generateExecId();
//            trade.setExecId(execId); // 生成12位execId
//            trade.setExecQty(tradeQty);
//            trade.setExecPrice(tradePrice);
//            trade.setTradeTime(LocalDateTime.now());
//            // 幂等校验：execId全局唯一，已存在则直接返回
//            if (tradeMapper.existsByExecId(execId) > 0) {
//                log.warn("成交记录已存在，幂等校验通过，execId={}", execId);
//                return;
//            }
//            // 区分买卖方（确保buyClOrderId和sellClOrderId正确）
//            if (orderA.getSide() == SideEnum.BUY) {
//                trade.setBuyClOrderId(orderA.getClOrderId());
//                trade.setSellClOrderId(orderB.getClOrderId());
//                trade.setBuyShareholderId(orderA.getShareholderId());
//                trade.setSellShareholderId(orderB.getShareholderId());
//            } else {
//                trade.setBuyClOrderId(orderB.getClOrderId());
//                trade.setSellClOrderId(orderA.getClOrderId());
//                trade.setBuyShareholderId(orderB.getShareholderId());
//                trade.setSellShareholderId(orderA.getShareholderId());
//            }
//            trade.setMarket(orderA.getMarket());
//            trade.setSecurityId(orderA.getSecurityId());
//
//            // 插入数据库
//            int insertCount = tradeMapper.insert(trade);
//            if (insertCount <= 0) {
//                log.error("成交记录插入失败：execId={}，成交数量={}", trade.getExecId(), trade.getExecQty());
//                throw new RuntimeException("成交记录插入失败");
//            }
//            log.info("成交记录插入成功：execId={}，买方订单={}，卖方订单={}，成交数量={}，成交价格={}",
//                    trade.getExecId(), trade.getBuyClOrderId(), trade.getSellClOrderId(),
//                    trade.getExecQty(), trade.getExecPrice());
//        } catch (Exception e) {
//            log.error("插入成交记录失败（订单A：{}，订单B：{}）", orderA.getClOrderId(), orderB.getClOrderId(), e);
//            throw new RuntimeException("撮合成交记录落地失败", e); // 抛出异常触发事务回滚
//        }
//    }
//}