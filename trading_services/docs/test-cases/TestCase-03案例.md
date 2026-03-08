## 第3类：对敲风控逻辑验证
### 需求点映射
| 测试子项 | 对应需求点 |
|------|------------|
| 3.1  | 对敲风控-同股东号同股票反向委托拦截 |
| 3.2  | 对敲风控-同股东号同股票同向委托通过 |
| 3.3  | 对敲风控-同股东号不同股票反向委托通过 |

---

#### 案例3.1：对敲风控拦截-同股东号同股票反向委托
**测试目标**：验证同一股东号对同一股票进行反向委托时，触发对敲风控拦截
**前置条件**：
1. 订单簿中已存在股东号 `SH00000001`、股票 `600030` 的**买单（BUY）**
```json
{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```

2. 数据库无该股东号该股票的反向挂单记录

**输入数据**：
```json
{
  "clOrderId": "SEL0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "S",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```

**预期输出**：
- 接口返回`RejectResponse`，提示对敲风控拦截
    ```json
    {
        "code": "3011",
        "msg": "对敲风险",
        "clOrderId": "SEL0000000000001",
        "market": "XSHG",
        "securityId": "600030",
        "side": "S",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```
- 数据库`t_exchange_order`无该订单插入记录
- 订单簿无该卖单加入记录

---

#### 案例3.2：对敲风控通过-同股东号同股票同向委托
**测试目标**：验证同一股东号对同一股票进行同向委托时，风控校验通过
**前置条件**：
1. 订单簿中已存在股东号 `SH00000001`、股票 `600030` 的**买单（BUY）**


**输入数据**：
```json
{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```

**预期输出**：
- 接口返回`OrderConfirmResponse`，提示订单已确认
    ```json
    {
        "code": "2000",
        "msg": "订单已确认（未成交）",
        "clOrderId": "BUY0000000000002",
        "market": "XSHG",
        "securityId": null,
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001",
        "orderStatus": "无对手单未成交"
    }
    ```
- 数据库`t_exchange_order`成功插入该订单记录
- 订单簿成功加入该买单记录

---


#### 案例3.3：对敲风控通过-同股东号不同股票反向委托
**测试目标**：验证同一股东号对不同股票进行反向委托时，风控校验通过
**前置条件**：
1. 订单簿中已存在股东号 `SH00000001`、股票 `600030` 的**买单（BUY）**

**输入数据**：
```json
{
  "clOrderId": "SEL0000000000003",
  "market": "XSHG",
  "securityId": "600031",
  "side": "S",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```

**预期输出**：
- 接口返回`OrderConfirmResponse`，提示订单已确认
    ```json
    {
        "code": "2000",
        "msg": "订单已确认（未成交）",
        "clOrderId": "SEL0000000000003",
        "market": "XSHG",
        "securityId": null,
        "side": "S",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001",
        "orderStatus": "无对手单未成交"
    }
    ```
- 数据库`t_exchange_order`成功插入该订单记录
- 订单簿成功加入该卖单记录