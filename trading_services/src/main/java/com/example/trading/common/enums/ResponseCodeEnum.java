package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 交易响应码枚举
 * 包含：成交成功、成交失败; 订单确认（未成交）; 订单拒绝(OrderStatusEnum)四类核心场景
 */
@Getter
public enum ResponseCodeEnum {
    // ====================== 成交相关响应 ======================
    /** 成交成功回报：部分/完全成交时返回 */
    TRADE_SUCCESS("0000", "成交成功（部分/完全成交时返回）"),
    /** 成交失败回报：撮合过程中出现异常导致成交失败 */
    TRADE_FAILED("1001", "成交失败（部分/完全成交时返回）"),

    // ====================== 订单确认相关响应 ======================
    /** 订单确认回报：未成交时返回，订单已接收并确认 */
    ORDER_CONFIRMED("2000", "订单已确认（未成交）"),
    /* 订单拒绝回报：订单校验失败/风控拒绝等，拒绝接收订单，见ErrorCodeEnum类&RejectResponse类 */

    // ====================== 订单撤销相关响应 ======================
    /** 撤单成功 */
    CANCEL_SUCCESS("3000", "撤单成功"),

    /** 撤单失败 */
    CANCEL_FAILED("3001", "撤单失败");


    /** 响应码 */
    private final String code;
    /** 响应描述 */
    private final String desc;

    /**
     * 构造方法
     * @param code 响应码
     * @param desc 响应描述
     */
    ResponseCodeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 静态方法：根据响应码获取枚举（便于外部调用）
     * @param code 响应码
     * @return 对应的枚举，无匹配则返回null
     */
    public static ResponseCodeEnum getByCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        for (ResponseCodeEnum enumObj : values()) {
            if (enumObj.getCode().equals(code)) {
                return enumObj;
            }
        }
        return null;
    }
}