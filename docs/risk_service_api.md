# Python 风控服务接口文档（IPC 接入指南）

> 本文档面向 Java 主控服务开发者，说明如何调用 Python 风控微服务。

## 1. 服务信息

| 项目 | 值 |
|------|-----|
| 服务名 | risk-service（Python） |
| 端口 | **9002** |
| 基础路径 | `http://localhost:9002` |
| 通信协议 | HTTP JSON |
| 协议定义 | `protocol/ipc/risk_check_request.schema.json`<br>`protocol/ipc/risk_check_response.schema.json` |

---

## 2. 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/risk/check` | 对敲风控检查（核心接口）|
| GET  | `/api/risk/health` | 健康检查 / 探活 |

---

## 3. 核心接口：对敲风控检查

### 3.1 请求

```
POST http://localhost:9002/api/risk/check
Content-Type: application/json
```

**请求体** 对应 `protocol/ipc/risk_check_request.schema.json`：

```json
{
  "incomingOrder": {
    "clOrderId": "B2",
    "shareholderId": "SH1",
    "market": "XSHG",
    "securityId": "600030",
    "side": "BUY",
    "qty": 100,
    "price": 10.5,
    "status": "NEW",
    "timestamp": 1700000000200
  },
  "existingOrders": [
    {
      "clOrderId": "S2",
      "shareholderId": "SH1",
      "market": "XSHG",
      "securityId": "600030",
      "side": "SELL",
      "qty": 100,
      "price": 10.5,
      "status": "NEW",
      "timestamp": 1700000000100
    }
  ]
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `incomingOrder` | Order | ✅ | 待检查的新订单 |
| `existingOrders` | Order[] | ✅ | 订单簿中已有的活跃订单（可为空数组 `[]`）|

**Order 字段**（对应 `protocol/order.schema.json`）：

| 字段 | 类型 | 必填 | 约束 | 说明 |
|------|------|------|------|------|
| `clOrderId` | string | ✅ | maxLength=16 | 订单唯一编号 |
| `shareholderId` | string | ✅ | maxLength=10 | 股东号 |
| `market` | string | ✅ | XSHG / XSHE / BJSE | 交易市场 |
| `securityId` | string | ✅ | maxLength=6 | 股票代码 |
| `side` | string | ✅ | BUY / SELL（也接受 B / S）| 买卖方向 |
| `qty` | integer | ✅ | ≥ 1 | 订单数量 |
| `price` | number | ✅ | ≥ 0 | 订单价格 |
| `status` | string | ❌ | OrderStatus 枚举 | 订单状态 |
| `timestamp` | integer | ❌ | 毫秒级时间戳 | 订单提交时间 |

### 3.2 响应

**成功响应**（HTTP 200）对应 `protocol/ipc/risk_check_response.schema.json`：

放行：
```json
{
  "allow": true,
  "reason": null
}
```

拦截：
```json
{
  "allow": false,
  "reason": "SELF_TRADE_DETECTED"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `allow` | boolean | `true` = 允许进入撮合，`false` = 风控拦截 |
| `reason` | string \| null | 拦截原因，`allow=true` 时为 `null` |

**错误响应：**

| HTTP 状态码 | 场景 | 响应体 |
|-------------|------|--------|
| 422 | 请求体字段校验失败（缺少必填字段、类型错误等）| Pydantic 标准错误格式 |
| 500 | 服务内部异常 | `{"detail": "风控检查异常：..."}` |

---

## 4. 健康检查接口

```
GET http://localhost:9002/api/risk/health
```

响应：
```json
{
  "status": "UP",
  "service": "risk-service",
  "port": 9002,
  "checks": {
    "selfTradeChecker": "enabled"
  }
}
```

---

## 5. Java 主控集成示例

### 5.1 在 `ExchangeService.processOrder()` 中调用

当前 Java 侧的风控检查流程（第 3 步）直接调用本地 `SelfTradeChecker`。  
如需集成 Python 风控服务，建议在本地风控检查之后、撮合之前增加 IPC 调用：

```java
// ===== ExchangeService.java 集成建议 =====

// 3. 本地对敲风控（保留，作为第一道防线）
ErrorCodeEnum riskError = selfTradeChecker.check(order);
if (riskError != null) {
    order.setStatus(OrderStatusEnum.RISK_REJECT);
    return buildRejectResponse(order, riskError);
}

// 3.5 Python 风控服务 IPC 调用（增强风控）
try {
    // 构建请求体
    Map<String, Object> riskRequest = new HashMap<>();
    riskRequest.put("incomingOrder", order);
    riskRequest.put("existingOrders", orderBook.getActiveOrders(order.getSecurityId()));
    
    String requestJson = JsonUtils.toJson(riskRequest);
    
    // HTTP POST 调用
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:9002/api/risk/check"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
        .timeout(Duration.ofSeconds(3))
        .build();
    
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    
    if (response.statusCode() == 200) {
        JsonNode result = JsonUtils.readTree(response.body());
        if (!result.get("allow").asBoolean()) {
            order.setStatus(OrderStatusEnum.RISK_REJECT);
            return buildRejectResponse(order, ErrorCodeEnum.SELF_TRADE);
        }
    }
} catch (Exception e) {
    // Python 服务不可用时降级为仅使用本地风控（已在上一步通过）
    log.warn("Python 风控服务调用失败，降级处理：{}", e.getMessage());
}

// 4. 撮合引擎处理
Order matchedOrder = matchingEngine.match(order);
```

### 5.2 application.yml 建议新增配置

```yaml
trading:
  risk:
    self-trade:
      enable: true
      time-window: 60000
    # 新增 Python 风控服务配置
    python-service:
      enable: true
      url: http://localhost:9002
      timeout: 3000  # 毫秒
```

### 5.3 注意事项

1. **Side 字段格式**：Java `SideEnum` 的 code 为 `"BUY"/"SELL"`，Python 服务兼容 `"B"/"S"` 和 `"BUY"/"SELL"` 两种格式，Java 侧直接传即可。

2. **降级策略**：建议 Python 服务不可用时，降级为仅使用 Java 本地 `SelfTradeChecker`，不影响核心交易流程。

3. **超时设置**：建议 HTTP 调用超时 ≤ 3 秒，避免风控调用拖慢订单处理。

4. **existingOrders 来源**：应传入对应股票代码下订单簿中所有**活跃状态**的订单（状态为 `NEW`、`VALID`、`MATCHING`、`PART_FILLED`）。

---

## 6. 直接测试（curl 示例）

### 6.1 对敲拦截场景

```bash
curl -X POST http://localhost:9002/api/risk/check \
  -H "Content-Type: application/json" \
  -d '{
    "incomingOrder": {
      "clOrderId": "B2",
      "shareholderId": "SH1",
      "market": "XSHG",
      "securityId": "600030",
      "side": "BUY",
      "qty": 100,
      "price": 10.5
    },
    "existingOrders": [{
      "clOrderId": "S2",
      "shareholderId": "SH1",
      "market": "XSHG",
      "securityId": "600030",
      "side": "SELL",
      "qty": 100,
      "price": 10.5
    }]
  }'
```

预期响应：`{"allow": false, "reason": "SELF_TRADE_DETECTED"}`

### 6.2 正常放行场景

```bash
curl -X POST http://localhost:9002/api/risk/check \
  -H "Content-Type: application/json" \
  -d '{
    "incomingOrder": {
      "clOrderId": "B1",
      "shareholderId": "SH1",
      "market": "XSHG",
      "securityId": "600030",
      "side": "BUY",
      "qty": 100,
      "price": 10.5
    },
    "existingOrders": [{
      "clOrderId": "S1",
      "shareholderId": "SH2",
      "market": "XSHG",
      "securityId": "600030",
      "side": "SELL",
      "qty": 100,
      "price": 10.5
    }]
  }'
```

预期响应：`{"allow": true, "reason": null}`

### 6.3 健康检查

```bash
curl http://localhost:9002/api/risk/health
```

---

## 7. 风控规则明细

| # | 条件 | 字段 | 说明 |
|---|------|------|------|
| 1 | 同一股东号 | `shareholderId` | 新订单与已有订单的股东号相同 |
| 2 | 同一股票 | `securityId` | 新订单与已有订单的股票代码相同 |
| 3 | 同一市场 | `market` | 新订单与已有订单的交易市场相同 |
| 4 | 相反方向 | `side` | 一个 BUY 一个 SELL |
| 5 | 价格可成交 | `price` | 买价 ≥ 卖价 |
| 6 | 时间窗口内 | `timestamp` | 两订单时间差 ≤ 60s（缺少时间戳则保守视为满足）|

**以上 6 条全部满足** → `allow=false, reason="SELF_TRADE_DETECTED"`  
**任一条不满足** → `allow=true, reason=null`
