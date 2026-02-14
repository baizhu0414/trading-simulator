"""
风控服务配置
对应 Java 侧 application.yml 中 trading.risk 的配置项
"""
import os

# 服务端口（与项目 README 约定一致：Python 风控服务 9002）
SERVER_PORT = int(os.getenv("RISK_SERVER_PORT", "9002"))

# 对敲检测时间窗口（毫秒），对应 Java 侧 trading.risk.self-trade.time-window
SELF_TRADE_TIME_WINDOW_MS = int(os.getenv("SELF_TRADE_TIME_WINDOW_MS", "60000"))

# 是否开启对敲风控，对应 Java 侧 trading.risk.self-trade.enable
SELF_TRADE_ENABLE = os.getenv("SELF_TRADE_ENABLE", "true").lower() == "true"
