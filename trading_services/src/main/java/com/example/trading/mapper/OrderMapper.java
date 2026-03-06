package com.example.trading.mapper;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.model.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
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

    int batchUpsert(@Param("orders") List<Order> orders);

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
     * 根据ID更新订单（含乐观锁：where version = #{version}）
     */
    int updateById(Order order);

    /**
     * 对手订单可能有多个，批量更新
     */
    int batchUpdateById(@Param("orders") List<Order> orders);

    /**
     * 判断订单是否存在
     */
    boolean existsByClOrderId(@Param("clOrderId") String clOrderId);

    /**
     * 查询所有未完成的订单（PROCESSING/MATCHING/NOT_FILLED）
     */
    List<Order> selectUnfinishedOrders();

    /**
     * 根据 clOrderId 强制更新状态和其他必要字段，忽略乐观锁版本
     * 用于解决撤单和下单持久化的时序竞争问题
     * @param order
     * @return
     */
    int forceUpdateStatusByClOrderId(@Param("order") Order order);
}