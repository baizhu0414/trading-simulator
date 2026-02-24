package com.example.trading.application.response;

import com.example.trading.common.enums.ResponseCodeEnum;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * 撤单确认响应（对齐任务书3.7规范）
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

    /** 原订单总数量 */
    private Integer qty;

    /** 原订单价格 */
    private BigDecimal price;

    /** 累计成交数量 */
    private Integer cumQty;

    /** 本次撤销数量 */
    private Integer canceledQty;

    /** 订单当前状态（通常为CANCELED或PARTIALLY_FILLED） */
    private String orderStatus;


    /**
     * 构建成功响应（仅基础返回码）
     */
    public static CancelConfirmResponse buildSuccess() {
        return CancelConfirmResponse.builder()
                .code(ResponseCodeEnum.CANCEL_SUCCESS.getCode())
                .msg(ResponseCodeEnum.CANCEL_SUCCESS.getDesc())
                .build();
    }

    /**
     * 构建完整撤单确认响应
     */
    public static CancelConfirmResponse buildSuccess(
            String clOrderId,
            String origClOrderId,
            String market,
            String securityId,
            String shareholderId,
            String side,
            Integer orderQty,
            BigDecimal orderPrice,
            Integer cumQty,
            Integer canceledQty,
            String orderStatus) {

        return CancelConfirmResponse.builder()
                .code(ResponseCodeEnum.CANCEL_SUCCESS.getCode())
                .msg(ResponseCodeEnum.CANCEL_SUCCESS.getDesc())
                .clOrderId(clOrderId)
                .origClOrderId(origClOrderId)
                .market(market)
                .securityId(securityId)
                .shareholderId(shareholderId)
                .side(side)
                .qty(orderQty)
                .price(orderPrice)
                .cumQty(cumQty)
                .canceledQty(canceledQty)
                .orderStatus(orderStatus)
                .build();
    }

    /**
     * 计算剩余数量（可选增强）
     */
    public Integer getLeavesQty() {
        if (qty == null || cumQty == null || canceledQty == null) {
            return null;
        }
        return qty - cumQty - canceledQty;
    }
}