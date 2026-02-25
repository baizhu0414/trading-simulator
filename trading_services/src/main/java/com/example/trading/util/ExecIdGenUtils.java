package com.example.trading.util;

import java.util.UUID;

public class ExecIdGenUtils {
    /**
     * 生成12位execId（从 TradePersistenceService 抽离）
     */
    public static String generateExecId() {
        String timestamp = String.valueOf(System.currentTimeMillis()).substring(4, 12);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return timestamp + random;
    }
}
