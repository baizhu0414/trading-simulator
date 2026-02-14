package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 订单状态枚举（优化：新增正常未成交状态，区分异常拒绝和正常未成交）
 * TODO：
 *      注意：此处的状态顺序不允许改动，因为数据库存储的就是顺序，后续可能需要优化。只允许在后面新增。
 */
@Getter
public enum OrderStatusEnum {
    NEW("NEW", "新建订单"),
    PROCESSING("PROCESSING", "处理中"),
    RISK_REJECT("RISK_REJECT", "风控拦截"), // 异常：风控拒绝
    MATCHING("MATCHING", "撮合中"),
    FULL_FILLED("FULL_FILLED", "完全成交"), // 正常：完全成交
    NOT_FILLED("NOT_FILLED", "无对手单未成交"), // 新增：正常未成交（核心）
    REJECTED("REJECTED", "非法订单"); // 异常：参数/校验错误拒绝

    private final String code;
    private final String desc;

    OrderStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 判断是否为未完成状态（仅待处理/处理中/撮合中，排除正常未成交）
     */
    public boolean isUnfinished() {
        return this == PROCESSING || this == MATCHING;
    }

    /**
     * 判断是否为失败状态（仅风控拦截/非法订单，排除正常未成交）
     */
    public boolean isFailed() {
        return this == RISK_REJECT || this == REJECTED;
    }

    /**
     * 判断是否为终态（完全成交/正常未成交/失败状态）
     */
    public boolean isFinalStatus() {
        return this == FULL_FILLED || this == NOT_FILLED || isFailed();
    }
}