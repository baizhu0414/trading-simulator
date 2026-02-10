package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 订单状态枚举
 */
@Getter
public enum OrderStatusEnum {
    NEW("NEW", "新建订单"),
    VALID("VALID", "校验通过"),
    RISK_REJECT("RISK_REJECT", "风控拦截"),
    MATCHING("MATCHING", "撮合中"),
    PART_FILLED("PART_FILLED", "部分成交"),
    FULL_FILLED("FULL_FILLED", "完全成交"),
    CANCELLED("CANCELLED", "已撤单"),
    REJECTED("REJECTED", "非法订单");

    private final String code;
    private final String desc;

    OrderStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}