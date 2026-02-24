package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 买卖方向枚举（适配B/S）
 */
@Getter
public enum SideEnum {
    BUY("B", "买入"),
    SELL("S", "卖出");

    private final String code; // 数据库存储值：B/S
    private final String desc;

    SideEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举（适配MyBatis映射）
     */
    public static SideEnum getByCode(String code) {
        for (SideEnum side : SideEnum.values()) {
            if (side.getCode().equals(code)) {
                return side;
            }
        }
        throw new IllegalArgumentException("无效的 side code: " + code);
    }
}