package com.example.trading.mapper;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.model.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 订单Mapper
 */
@Mapper
public interface OrderMapper {

    /**
     * 插入订单
     */
    int insert(Order order);

    /**
     * 订单成交或其他更新都应该用此处代码，避免全量更新。
     */
    int batchUpdateStatusQtyVersionById(@Param("orders") List<Order> orders);
    int updateStatusQtyVersionById(Order order);
    List<Order> selectByClOrderIds(@Param("clOrderIds") List<String> clOrderIds);
    // 保留@Param("orders")：和Mapper.xml中collection="orders"匹配
    int batchInsert(@Param("orders") List<Order> orders);

    /**
     * 仅用于布隆过滤器查ID
     */
    List<String> selectAllClOrderIds();

    /**
     * 根据业务单号查询订单
     */
    Optional<Order> selectByClOrderId(@Param("clOrderId") String clOrderId);

    /**
     * 根据状态列表查询订单
     */
    List<Order> selectByStatusIn(@Param("statusList") List<OrderStatusEnum> statusList);

    /**
     * 数据一致性验证只需要部分数据
     */
    List<Order> selectPartByStatusIn(@Param("statusList") List<OrderStatusEnum> statusList);

    /**
     * 根据ID更新订单（含乐观锁：where version = #{version}）
     */
    int updateTradedOrderByOrderId(Order order);

    // 保留@Param("orders")：和Mapper.xml中collection="orders"匹配
    int batchUpdateTradeOrderByOrderId(@Param("orders") List<Order> orders);

    /**
     * 批量更新撤单（无乐观锁）
     */
    int batchUpdateCancelOrderByOrderId(@Param("orders") List<Order> canceledOrders);

    /**
     * 判断订单是否存在
     */
    boolean existsByClOrderId(@Param("clOrderId") String clOrderId);
}