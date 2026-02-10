**开发IPC前必须沟通确认**
```
protocol/
├── README.md
├── enums.md 统一枚举定义
├── order.schema.json 交易订单
├── cancel.schema.json 交易撤单
├── market_data.schema.json 行情信息
├── ack.schema.json 交易所订单确认回报
├── reject.schema.json 交易所订单非法回报
├── trade.schema.json 交易所订单成交回报
├── cancel_ack.schema.json 撤单确认回报
├── cancel_reject.schema.json 撤单非法回报
└── ipc/(*此处接口需要沟通后确定，目前仅仅给出了参考*)
    ├── match_request.schema.json 撮合服务接口（无状态计算单元）
    ├── match_response.schema.json 成交回报结构
    ├── risk_check_request.schema.json 对敲风控
    ├── risk_check_response.schema.json
└── examples/
    ├── order.json
    ├── cancel.json
    ├── market_data.json 行情
    ├── ack.json 交易所订单确认回报（用不到）
    ├── reject.json 交易所订单非法回报
    ├── trade.json 交易所订单成交回报
    ├── cancel_ack.json 交易所撤单确认回报
    └── cancel_reject.json 撤单非法回报

```

