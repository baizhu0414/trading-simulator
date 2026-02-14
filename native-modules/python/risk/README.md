# Python Risk Management Service

## 简介

这是交易系统的风控模块，负责实时评估交易风险。

## 启动服务

### 前置条件

- Python 3.8+

### 运行

```bash
python main.py
```

服务将监听 `0.0.0.0:9002`。

## 通信协议

目前使用简单的 TCP Socket 通信（Echo 模式），后续将对接 Protobuf 协议。
