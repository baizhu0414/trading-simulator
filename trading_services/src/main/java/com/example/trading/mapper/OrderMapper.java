package com.example.trading.mapper;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.domain.model.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单Mapper（MyBatis版，替代JPA的Repository）
 */
@Mapper // 标记为MyBatis Mapper，或在启动类加@MapperScan("com.example.trading.mapper")
public interface OrderMapper {

    /**
     * 插入订单（返回自增主键）
     */
    int insert(Order order);

    /**
     * 根据业务单号查询订单（幂等校验核心）
     */
    Optional<Order> selectByClOrderId(@Param("clOrderId") String clOrderId);

    /**
     * 根据状态列表查询订单（恢复服务分页查询用）
     */
    List<Order> selectByStatusIn(@Param("statusList") List<OrderStatusEnum> statusList);

    // 带超时过滤的状态查询
    List<Order> selectByStatusInAndCreateTimeAfter(
            @Param("statusList") List<OrderStatusEnum> statusList,
            @Param("expireTime") LocalDateTime expireTime);

    /**
     * 根据ID更新订单（含乐观锁：where version = #{version}）
     */
    int updateById(Order order);

    /**
     * 对手订单可能有多个，批量更新
     */
    int batchUpdateById(@Param("orders") List<Order> orders);

    /**
     * 判断订单是否存在（幂等校验快速判断）
     */
    boolean existsByClOrderId(@Param("clOrderId") String clOrderId);

    /**
     * 查询所有未完成的订单（PROCESSING/MATCHING/NOT_FILLED）
     */
    List<Order> selectUnfinishedOrders();
}