package com.example.trading.domain.model;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体（MyBatis版，适配字段规则）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 数据库自增主键 */
    private Long id;
    /** 订单唯一编号（业务唯一标识，固定16位） */
    private String clOrderId;
    /** 股东号（固定10位） */
    private String shareholderId;
    /** 交易市场（仅XSHG/XSHE/BJSE） */
    private String market;
    /** 股票代码 */
    private String securityId;
    /** 买卖方向（B/S，对应SideEnum的code） */
    private SideEnum side;
    /** 订单数量（无符号32位整数≥0，支持零股） */
    private Integer qty;
    /** 订单价格（BigDecimal避免精度问题） */
    private BigDecimal price;
    /** 订单状态 */
    private OrderStatusEnum status;
    /** 订单创建时间 */
    private LocalDateTime createTime;
    /** 乐观锁版本号（解决并发修改导致的状态不一致） */
    private Integer version;

    // Builder模式自动填充创建时间
    public static class OrderBuilder {
        private LocalDateTime createTime = LocalDateTime.now();
        private Integer version = 0; // 默认版本号0
    }
}