package com.example.trading.common.enums;

/**
 * 持久化信号类型（对应你的3种持久化场景）
 */
public enum PersistSignalType {
    ORDER_AND_TRADES, // 订单+交易记录持久化
    CANCEL_ORDER,     // 撤单持久化
    RECOVERY_RESULT   // 恢复结果持久化
}