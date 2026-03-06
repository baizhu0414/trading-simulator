package com.example.trading.common.enums;

import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
public enum ErrorCodeEnum {
    PARAM_NULL(3000, "参数为空"),
    PARAM_FORMAT_ERROR(3002, "参数格式错误"),
    MARKET_INVALID(3003, "交易市场非法"),
    SIDE_INVALID(3004, "买卖方向非法"),
    QTY_INVALID(3005, "订单数量非法"),
    PRICE_INVALID(3006, "订单价格非法"),
    QTY_NOT_MULTIPLE_100(3009, "订单数量非法（必须是100的整数倍）"),
    SELF_TRADE(3011, "对敲风险"),
    ORDER_EXISTED(3012, "订单号已存在"),
    REDIS_UNAVAILABLE(3013, "Redis不可用"),

    // 数据库相关错误码
    DB_FIELD_LENGTH_EXCEED(4001, "数据库字段长度超限"),
    DB_UNIQUE_CONSTRAINT_VIOLATION(4002, "订单号重复（唯一约束违反）"),
    DB_CHECK_CONSTRAINT_VIOLATION(4003, "字段值违反数据库约束规则"),
    DB_INSERT_FAILED(4004, "数据库插入失败（未知原因）"),

    // 撤单错误
    CANCEL_ORDER_EXISTED(5001, "撤单请求号已存在"),
    CANCEL_INFO_MISMATCH(5003, "撤单信息与原订单不匹配"),
    ORDER_NOT_CANCELABLE(5004, "订单状态不可撤销"),
    ORDER_NOT_IN_ORDER_BOOK(5005, "订单不在订单簿中，无法撤销"),
    CANCEL_PROCESS_FAILED(5006, "订单撤销处理失败"),
    SYSTEM_BUSY(9999, "系统繁忙，请重试");

    private final Integer code;
    private final String desc;

    ErrorCodeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据异常信息匹配错误码
    public static ErrorCodeEnum matchDbError(Exception e) {
        String errorMsg = e.getMessage();
        if (errorMsg == null) {
            return DB_INSERT_FAILED;
        }
        // 匹配MySQL字段长度超限错误
        if (errorMsg.contains("Data truncation") || errorMsg.contains("too long for column")) {
            return DB_FIELD_LENGTH_EXCEED;
        }
        // 匹配唯一约束
        if (errorMsg.contains("Duplicate entry") && errorMsg.contains("idx_cl_order_id")) {
            return DB_UNIQUE_CONSTRAINT_VIOLATION;
        }
        // 匹配CHECK约束违反
        if (errorMsg.contains("CHECK constraint failed") || errorMsg.contains("chk_")) {
            return DB_CHECK_CONSTRAINT_VIOLATION;
        }
        // 其他数据库插入错误
        return DB_INSERT_FAILED;
    }
}