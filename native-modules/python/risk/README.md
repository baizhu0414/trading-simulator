# Python 风控微服务（Risk Service）

## 概述

基于 FastAPI 的证券交易**对敲风控检测服务**。  
通过 HTTP JSON IPC 与 Java 主控服务通信，在订单进入撮合引擎之前进行风控拦截。

| 项目 | 说明 |
|------|------|
| 端口 | **9002** |
| 语言 | Python 3.10+ |
| 框架 | FastAPI + Pydantic + uvicorn |
| 协议 | `protocol/ipc/risk_check_request.schema.json` / `risk_check_response.schema.json` |

## 快速启动

```bash
# 1. 进入服务目录
cd native-modules/python/risk

# 2. 安装依赖
pip install -r requirements.txt

# 3. 启动服务
python main.py
```

启动后：
- 风控检查接口：`POST http://localhost:9002/api/risk/check`
- 健康检查接口：`GET  http://localhost:9002/api/risk/health`
- Swagger 文档：`http://localhost:9002/docs`

## 运行测试

```bash
cd native-modules/python/risk
pytest tests/ -v
```

## 接口文档

详见 [docs/risk_service_api.md](../../../docs/risk_service_api.md)

## 风控规则

对敲判定条件（**全部满足**才会拦截）：

| # | 条件 | 说明 |
|---|------|------|
| 1 | 同一股东号 | `shareholderId` 相同 |
| 2 | 同一股票 | `securityId` 相同 |
| 3 | 同一市场 | `market` 相同 |
| 4 | 相反方向 | BUY ↔ SELL |
| 5 | 价格可成交 | 买价 ≥ 卖价 |
| 6 | 时间窗口内 | 默认 60s，可通过环境变量配置 |

## 配置项

通过环境变量配置（见 `config.py`）：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `RISK_SERVER_PORT` | 9002 | 服务端口 |
| `SELF_TRADE_TIME_WINDOW_MS` | 60000 | 对敲检测时间窗口（毫秒）|
| `SELF_TRADE_ENABLE` | true | 是否开启对敲风控 |

## 与 Java 主控的关系

- Java 侧 `SelfTradeChecker` 基于内存 `ConcurrentHashMap` 做简单方向缓存判断
- Python 版为**增强实现**：基于 Java 传入的 `existingOrders` 列表做精确逐笔匹配
- Java 主控通过 HTTP POST 调用本服务，将新订单和订单簿中活跃订单一并传入

## 目录结构

```
native-modules/python/risk/
├── main.py                    # FastAPI 入口
├── config.py                  # 配置项
├── requirements.txt           # 依赖
├── models/
│   ├── __init__.py
│   └── schemas.py             # Pydantic 数据模型（对应 protocol/ 中的 schema）
├── services/
│   ├── __init__.py
│   └── self_trade_checker.py  # 对敲风控核心逻辑
└── tests/
    ├── __init__.py
    └── test_self_trade.py     # 单元测试 + 集成测试
```

## Side 字段兼容性

`protocol/order.schema.json` 定义 side 为 `"B"/"S"`，但 IPC 示例和 Java 枚举使用 `"BUY"/"SELL"`。  
本服务**两种格式都接受**，内部统一转换为 `BUY`/`SELL`。
