package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
public enum ErrorCodeEnum {
    // 基础校验错误
    PARAM_NULL(1001, "必填字段为空"),
    MARKET_INVALID(1002, "交易市场不合法（仅支持XSHG/XSHE/BJSE）"),
    SIDE_INVALID(1003, "买卖方向不合法（仅支持B/S）"),
    QTY_INVALID(1004, "订单数量必须大于0"),
    PRICE_INVALID(1005, "订单价格必须大于等于0"),
    // 风控错误
    SELF_TRADE(2001, "同一股东号存在对敲交易"),
    // 撮合错误
    MATCH_FAILED(3001, "撮合失败");

    private final int code;
    private final String msg;

    ErrorCodeEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}