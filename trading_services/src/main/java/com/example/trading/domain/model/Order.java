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
 * 订单实体（新增originalQty字段）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order implements Serializable {
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
    /** 原始订单数量（创建时固定，不修改） */
    private Integer originalQty; // 新增：原始数量
    /** 剩余订单数量（无符号32位整数≥0，支持零股） */
    private Integer qty; // 原qty改为剩余数量
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
        private Integer version = 0;

        // 修复：先初始化originalQty，避免空指针
        private Integer originalQty;

        // 重构qty赋值逻辑：确保originalQty只在首次赋值时设置
        public OrderBuilder qty(Integer qty) {
            this.qty = qty;
            // 仅当originalQty未设置时，才用qty初始化（避免覆盖）
            if (this.originalQty == null) {
                this.originalQty = qty;
            }
            return this;
        }

        // 显式提供originalQty赋值方法（可选，增强灵活性）
        public OrderBuilder originalQty(Integer originalQty) {
            this.originalQty = originalQty;
            return this;
        }
    }
}