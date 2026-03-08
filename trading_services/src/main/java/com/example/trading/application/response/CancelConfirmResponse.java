package com.example.trading.application.response;

import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * 撤单确认响应
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class CancelConfirmResponse extends BaseResponse {

    /** 撤单请求唯一编号 */
    private String clOrderId;

    /** 原订单唯一编号 */
    private String origClOrderId;

    /** 交易市场 */
    private String market;

    /** 股票代码 */
    private String securityId;

    /** 股东号 */
    private String shareholderId;

    /** 买卖方向 B/S */
    private String side;

    /** 剩余订单数量，也就是取消订单数量，定义和Order保持一致 */
    private Integer qty;

    /** 原订单价格 */
    private BigDecimal price;

    /** 累计成交数量 */
    private Integer cumQty;

    /** 订单当前状态（通常为CANCELED或PART_FILLED） */
    private String orderStatus;

}