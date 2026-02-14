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

### 接口定义

当前服务旨在实现 `protocol/ipc` 目录下的协议规范（需进一步沟通确认）：

- **请求**: 参考 `protocol/ipc/risk_check_request.schema.json`
- **响应**: 参考 `protocol/ipc/risk_check_response.schema.json`

### 当前状态

目前仅实现了基础的 TCP Socket 监听（Echo 模式），用于连通性测试。后续将集成 `protobuf` 或 JSON 解析逻辑以符合上述 Schema。
