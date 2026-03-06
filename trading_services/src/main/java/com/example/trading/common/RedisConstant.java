package com.example.trading.common;

public class RedisConstant {
    /** 订单非终态的前缀 ,防止未持久化就出现重复订单。因此不需要在扯淡后删除此订单。 */
    public static final String ORDER_PROCESSING_PREFIX = "trading:order:processing:";
    /** 处理中状态值 */
    public static final String STATUS_PROCESSING = "1";
    /** 处理中Key的过期时间，防止死锁 */
    public static final long PROCESSING_KEY_TTL_MINUTES = 5;
    /** 已完成状态值 (墓碑标记) */
    public static final String STATUS_DONE = "DONE";
    /** 订单完成后Key的保留时间 (幂等窗口期，建议设长一点，比如1小时) */
    public static final long DONE_KEY_TTL_HOURS = 1;
}
