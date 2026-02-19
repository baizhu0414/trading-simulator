## 第4类：撮合引擎规则验证
### 需求点映射
| 测试子项 | 对应需求点 |
|------|------------|
| 4.1  | 撮合规则 价高者得&价格规则&多次成交 |
| 4.2  | 撮合规则-时间优先（同价格下早提交的订单优先被匹配） |
| 4.3  | 撮合失败-价格不匹配（买单价格 < 卖单价格） |
| 4.4  | 撮合部分成交-对手方数量不足 |

---

#### 案例4.1：撮合规则&价格规则&多次成交
**测试目标**：验证价格优先规则，低价卖单优先被高价买单匹配
**前置条件**：
1. 订单簿中已存在两笔卖单：
   - 卖单1：`SEL0000000000001`，价格 `20.10`，数量 `100`，时间 `10:00:00`
   - 卖单2：`SEL0000000000002`，价格 `20.00`，数量 `100`，时间 `10:00:01`
   - 卖单3：`SEL0000000000003`，价格 `20.20`，数量 `200`，时间 `10:00:01`
    ```
    {
        "clOrderId": "SEL0000000000001",
        "market": "XSHG",
        "securityId": "600030",
        "side": "S",
        "price": 20.10,
        "qty": 100,
        "shareholderId": "SH00000001"
    }

    {
        "clOrderId": "SEL0000000000002",
        "market": "XSHG",
        "securityId": "600030",
        "side": "S",
        "price": 20.0,
        "qty": 100,
        "shareholderId": "SH00000001"
    }

    {
        "clOrderId": "SEL0000000000003",
        "market": "XSHG",
        "securityId": "600030",
        "side": "S",
        "price": 20.2,
        "qty": 200,
        "shareholderId": "SH00000001"
    }
    ```
2. 数据库中已成功插入上述两笔卖单记录

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000004",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.30,
  "qty": 300,
  "shareholderId": "SH00000002"
}
```

**预期输出**：
- 接口返回`TradeResponse`，提示成交成功
    ```json
    {
        "code": "0000",
        "msg": "成交成功（部分/完全成交时返回）",
        "clOrderId": "BUY0000000000004",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "orderQty": 300,
        "orderPrice": 20.3,
        "shareholderId": "SH00000002",
        "orderStatus": "完全成交",
        "tradeResponses": [
            {
                "execId": "520999017CC9",
                "execQty": 100,
                "execPrice": 20.15,
                "execTime": "2026-02-20T01:09:59.0198373",
                "orderStatus": "完全成交"
            },
            {
                "execId": "52099901A491",
                "execQty": 100,
                "execPrice": 20.2,
                "execTime": "2026-02-20T01:09:59.0198373",
                "orderStatus": "完全成交"
            },
            {
                "execId": "52099901A862",
                "execQty": 100,
                "execPrice": 20.25,
                "execTime": "2026-02-20T01:09:59.0198373",
                "orderStatus": "部分成交"
            }
        ]
    }
    ```


---

#### 案例4.2：撮合规则-时间优先（同价格下早提交的订单优先被匹配）
**测试目标**：验证时间优先规则，同价格下早提交的订单优先被匹配

**前置条件**：
1. 订单簿中已存在两笔卖单：
   - 卖单1：`SEL0000000000004`，价格 `20.10`，数量 `100`，时间 `10:00:00`
   - 卖单2：`SEL0000000000005`，价格 `20.10`，数量 `100`，时间 `10:00:01`
    ```
    {
        "clOrderId": "SEL0000000000004",
        "market": "XSHG",
        "securityId": "600030",
        "side": "S",
        "price": 20.10,
        "qty": 100,
        "shareholderId": "SH00000001"
    }

    {
        "clOrderId": "SEL0000000000005",
        "market": "XSHG",
        "securityId": "600030",
        "side": "S",
        "price": 20.10,
        "qty": 100,
        "shareholderId": "SH00000001"
    }
    ```

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000006",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.20,
  "qty": 100,
  "shareholderId": "SH00000002"
}
```

**预期输出**：
- 接口返回`TradeResponse`，提示成交成功
    ```json
    {
        "code": "0000",
        "msg": "成交成功（部分/完全成交时返回）",
        "clOrderId": "BUY0000000000006",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "orderQty": 100,
        "orderPrice": 20.2,
        "shareholderId": "SH00000002",
        "orderStatus": "完全成交",
        "tradeResponses": [
            {
                "execId": "52148723F82F",
                "execQty": 100,
                "execPrice": 20.15,
                "execTime": "2026-02-20T01:18:07.2308333",
                "orderStatus": "完全成交"
            }
        ]
    }
    ```

---

#### 案例4.3：撮合失败-价格不匹配（买单价格 < 卖单价格）
**测试目标**：验证价格不匹配时，撮合失败，订单加入订单簿
**前置条件**：
1. 订单簿中已存在一笔卖单：
   - 卖单1：`SEL0000000000005`，价格 `20.10`，数量 `100`，时间 `10:00:00`
2. 数据库中已成功插入该卖单记录

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000007",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000003"
}
```

**预期输出**：
- 接口返回`OrderConfirmResponse`，提示订单已确认（未成交）
    ```json
    {
        "code": "2000",
        "msg": "订单已确认（未成交）",
        "clOrderId": "BUY0000000000007",
        "market": "XSHG",
        "securityId": null,
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000003",
        "orderStatus": "无对手单未成交"
    }
    ```

---

#### 案例4.4：撮合部分成交-对手方数量不足
**测试目标**：验证对手方数量不足时，撮合部分成交，剩余数量加入订单簿
**前置条件**：
1. 订单簿中已存在一笔卖单：
   - 卖单1：`SEL0000000000006`，价格 `20.00`，数量 `100`，时间 `10:00:00`
2. 数据库中已成功插入该卖单记录

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000008",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 21.00,
  "qty": 600,
  "shareholderId": "SH00000004"
}
```

**预期输出**：
- 接口返回`TradeResponse`，提示部分成交
    ```json
    {
        "code": "0000",
        "msg": "成交成功（部分/完全成交时返回）",
        "clOrderId": "BUY0000000000008",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "orderQty": 600,
        "orderPrice": 21,
        "shareholderId": "SH00000004",
        "orderStatus": "部分成交",
        "tradeResponses": [
            {
                "execId": "52182117C257",
                "execQty": 100,
                "execPrice": 20.55,
                "execTime": "2026-02-20T01:23:41.1717403",
                "orderStatus": "完全成交"
            },
            {
                "execId": "52182117B669",
                "execQty": 100,
                "execPrice": 20.6,
                "execTime": "2026-02-20T01:23:41.1717403",
                "orderStatus": "完全成交"
            }
        ]
    }
    ```

---

