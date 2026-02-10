package com.example.trading.domain.model;

import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单实体类（对应题目JSON结构）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order implements Serializable {
    /**
     * 订单唯一编号（char[16]）
     */
    private String clOrderId;
    /**
     * 股东号（char[10]）
     */
    private String shareholderId;
    /**
     * 交易市场（XSHG/XSHE/BJSE）
     */
    private String market;
    /**
     * 股票代码（char[6]）
     */
    private String securityId;
    /**
     * 买卖方向
     */
    private SideEnum side;
    /**
     * 订单数量（uint32）
     */
    private Integer qty;
    /**
     * 订单价格
     */
    private Double price;
    /**
     * 订单状态
     */
    private OrderStatusEnum status;
    /**
     * 订单提交时间戳
     */
    private Long timestamp;
}