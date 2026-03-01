package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 订单状态枚举
 * **注意**：真正开始使用之后，此处的状态顺序不允许改动，因为数据库存储的就是顺序，后续可能需要优化。只允许在后面新增。
 */
@Getter
public enum OrderStatusEnum {
    PROCESSING("PROCESSING", "处理中"), // 0
    RISK_REJECT("RISK_REJECT", "风控拦截"), // status=1 风控拒绝
    MATCHING("MATCHING", "撮合中"), // 2
    NOT_FILLED("NOT_FILLED", "无对手单未成交"), // 3 未成交，可全量撤单
    PART_FILLED("PART_FILLED", "部分成交"), // 4 部分成交，可全量撤单
    CANCELED("CANCELED", "已撤销"), // 5 终态，不可再次撤单/撮合
    FULL_FILLED("FULL_FILLED", "完全成交"), // 6 完全成交
    REJECTED("REJECTED", "非法订单"); // 7异常：参数/校验错误拒绝——不入库了

    private final String code;
    private final String desc;

    OrderStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 判断是否为未完成状态
     */
    public boolean isUnfinished() {
        return this == PROCESSING || this == MATCHING || this == PART_FILLED;
    }

    /**
     * 判断是否为失败状态
     */
    public boolean isFailed() {
        return this == RISK_REJECT || this == REJECTED;
    }

    /**
     * 扩展：判断是否为成交相关状态
     */
    public boolean isFilled() {
        return this == FULL_FILLED || this == PART_FILLED;
    }

    /**
     * 判断是否为终态
     */
    public boolean isFinalStatus() {
        return this == FULL_FILLED || isFailed() || this==CANCELED;
    }

    /*判断订单是否可撤销*/
    public boolean isCancelable() {
        return this == NOT_FILLED || this == PART_FILLED;
    }
}