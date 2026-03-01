package com.example.trading.domain.model;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 显式指定equals/hashCode只包含业务唯一标识clOrderId
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Order implements Serializable {
    private static final long serialVersionUID = 1L; // 序列化最佳实践
    /** 数据库自增主键 */
    private Long id;
    /** 订单唯一编号（业务唯一标识，固定16位） */
    @EqualsAndHashCode.Include
    private String clOrderId;
    /** 股东号（固定10位） */
    private String shareholderId;
    /** 交易市场（仅XSHG/XSHE/BJSE） */
    private String market;
    /** 股票代码 */
    private String securityId;
    /** 买卖方向（B/S，对应SideEnum的code） */
    private SideEnum side;
    /** 原始订单数量 */
    private Integer originalQty; // 原始数量
    /** 剩余订单数量（无符号32位整数≥0） */
    private Integer qty; // 原qty改为剩余数量
    /** 订单价格（BigDecimal避免精度问题，2位小数） */
    private BigDecimal price;
    /** 订单状态 */
    private OrderStatusEnum status;
    /** 订单创建时间 */
    @Builder.Default
    private LocalDateTime createTime  = LocalDateTime.now();;
    /** 乐观锁版本号 */
    private Integer version;
}