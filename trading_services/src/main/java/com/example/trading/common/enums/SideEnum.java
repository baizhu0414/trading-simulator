package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 订单买卖方向枚举
 */
@Getter
public enum SideEnum {
    BUY("BUY", "买入"),
    SELL("SELL", "卖出");

    private final String code;
    private final String desc;

    SideEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据编码获取枚举
     */
    public static SideEnum getByCode(String code) {
        for (SideEnum side : values()) {
            if (side.getCode().equals(code)) {
                return side;
            }
        }
        return null;
    }
}