package com.example.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对敲风控配置属性绑定类
 * prefix指定配置的前缀，要和yml中的层级对应
 */
@Component
@ConfigurationProperties(prefix = "trading.risk.self-trade")
public class RiskConfig {

    // 对应yml中的enable属性
    private boolean enable;
    // 对应yml中的time-window属性
    private long timeWindow;

    // Getter和Setter方法（必须提供，否则无法绑定）
    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public long getTimeWindow() {
        return timeWindow;
    }

    public void setTimeWindow(long timeWindow) {
        this.timeWindow = timeWindow;
    }
}
