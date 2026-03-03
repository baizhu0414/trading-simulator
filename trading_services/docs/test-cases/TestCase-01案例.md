## 第1类：核心必选功能验证（覆盖指定4个需求点）
### 需求点映射
| 测试子项 | 对应需求点               |
|------|---------------------|
| 1.1  | 实现股票交易以手为单位         |
| 1.2  | 创建成功交易数据库表          |
| 1.3  | 实现一个订单的多次交易         |
| 1.4  | 优化事务回滚内存一致性控制       |
| 1.5  | 测试应用崩溃重启后，数据加载和撮合流程 |

---

#### 案例1.1.1：股票交易以手为单位-正常整手委托
**测试目标**：验证零股开关关闭时，仅接受100整数倍的订单
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
- 接口返回`OrderConfirmResponse`，订单正常挂单
    ```
    {
        "code": "2000",
        "msg": "订单已确认（未成交）",
        "clOrderId": "BUY0000000000001",
        "market": "XSHG",
        "securityId": null,
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001",
        "orderStatus": "无对手单未成交"
    }
    ```
- 数据库`t_exchange_order`插入成功，`qty=100`

---

#### 案例1.1.2：股票交易以手为单位-非整手委托拦截
**测试目标**：验证零股开关关闭时，非100整数倍订单被业务层拦截
**输入数据**：
```json
{
  "clOrderId": "BUY0000000000002",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 150,
  "shareholderId": "SH00000001"
}
```
**预期输出**：
- 接口返回`RejectResponse`，`rejectCode=3009`，`rejectText="订单数量非法（必须是100的整数倍）"`
    ```
    {
        "code": "3009",
        "msg": "订单数量非法（必须是100的整数倍）",
        "clOrderId": "BUY0000000000002",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "qty": 150,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```

---

#### 案例1.2.1：创建成功交易数据库表-完全成交
**测试目标**：验证`t_exchange_trade`表能成功插入完整成交记录

**前置条件**：
- 已提交卖单：`SEL00000000000001`（XSHG，600030，S，20.00，100，SH000000002）

**输入数据**：
```json
{
  "clOrderId": "SEL0000000000112",
  "market": "XSHG",
  "securityId": "600030",
  "side": "S",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000002"
}
```
**预期输出**：
```json
{
	"code": "0000",
	"msg": "成交成功（部分/完全成交时返回）",
	"clOrderId": "SEL0000000000112",
	"market": "XSHG",
	"securityId": "600030",
	"side": "S",
	"orderQty": 100,
	"orderPrice": 20,
	"shareholderId": "SH00000002",
	"orderStatus": "完全成交",
	"tradeResponses": [
		{
			"execId": "466905791F8E",
			"execQty": 100,
			"execPrice": 20,
			"execTime": "2026-02-19T10:08:25.8007979",
			"orderStatus": "完全成交"
		}
	]
}
```

---

#### 案例1.3.1：一个订单的多次交易-分笔成交逐笔回报
**测试目标**：验证一个订单匹配多笔对手单时，返回`List<TradeResponse>`，每笔独立
**前置条件**：
- 已提交卖单1：
```json
{
  "clOrderId": "SEL0000000000002",
  "market": "XSHG",
  "securityId": "600030",
  "side": "S",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000002"
}
```
- 已提交卖单2：
```json
{
  "clOrderId": "SEL0000000000003",
  "market": "XSHG",
  "securityId": "600030",
  "side": "S",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000003"
}
```

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000004",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 300,
  "shareholderId": "SH00000001"
}
```

**预期输出**：
```json
{
	"code": "0000",
	"msg": "成交成功（部分/完全成交时返回）",
	"clOrderId": "BUY0000000000004",
	"market": "XSHG",
	"securityId": "600030",
	"side": "B",
	"orderQty": 300,
	"orderPrice": 20,
	"shareholderId": "SH00000001",
	"orderStatus": "部分成交",
	"tradeResponses": [
		{
			"execId": "467489304A3E",
			"execQty": 100,
			"execPrice": 20,
			"execTime": "2026-02-19T10:18:09.3090774",
			"orderStatus": "完全成交"
		},
		{
			"execId": "467489306E9F",
			"execQty": 100,
			"execPrice": 20,
			"execTime": "2026-02-19T10:18:09.3090774",
			"orderStatus": "完全成交"
		}
	]
}
```

---

#### 案例1.4.1：成交事务回滚内存一致性控制-模拟异常回滚
**测试目标**：验证事务回滚时，内存`OrderBook`中的订单能被正确清理

**前置条件**：
- 通用前置准备已完成
- 临时修改代码：在`MatchingEngine.match`方法中，成交记录插入后抛出`RuntimeException("模拟异常")`
    ```java
    if(trades.isEmpty()) {
        throw new RuntimeException("模拟异常"); // 测试回滚
    }
    ```

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000005",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```

**操作步骤**：
1. 先提交卖单
```sql
INSERT INTO t_exchange_order (
    cl_order_id, 
    shareholder_id, 
    market, 
    security_id, 
    side, 
    qty, 
    original_qty, 
    price, 
    status, 
    create_time, 
    version
) VALUES (
    'SEL0000000000004', -- 16位订单号
    'SH00000002',       -- 10位股东号
    'XSHG',              -- 合法市场
    '600030',            -- 6位股票代码
    'S',                 -- 合法买卖方向
    100,                 -- 订单数量（无符号）
    100,                 -- 原始订单数量（与qty一致）
    20.00,               -- 价格（decimal(10,2)）
    '3',        -- 订单状态（未成交挂单）
    NOW(),               -- 创建时间
    0                    -- 乐观锁版本号（默认0）
);
```
2. 提交上述买单，触发模拟异常
3. 等待事务回滚
4. 查询内存`OrderBook`、数据库
```json
{
	"code": "4004",
	"msg": "数据库插入失败（未知原因）",
	"clOrderId": "BUY0000000000005",
	"market": "XSHG",
	"securityId": "600030",
	"side": "B",
	"qty": 0,
	"price": 20,
	"shareholderId": "SH00000001"
}
```

**预期输出**：
- 数据库`t_exchange_order`：新增一笔状态未MATCHING的记录（事务外执行，重启会自动重新撮合），无成交记录
    ```
    2	BUY0000000000005	SH00000001	XSHG	600030	B	100	100	20.00	0	2026-02-19 15:39:26	0
    ```
- 内存`OrderBook`：2笔订单均在队列中（未被移除）
- **应用回滚事务关键日志**：
  ```logcatfilter
  1. 业务逻辑执行：
  2026-02-19 15:39:25.796 [http-nio-8081-exec-2] INFO  c.example.trading.application.TradeResponseHelper - 订单[BUY0000000000005]批量插入1笔成交记录
  2. 异常触发点：
  java.lang.RuntimeException: 模拟异常
      at com.example.trading.application.TradeResponseHelper.executeOrderTransaction(TradeResponseHelper.java:85)
  
  3. Spring 事务切面介入（回滚决策）
  at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:380)
  at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)
  
  4. 异常传播日志：
  2026-02-19 15:39:25.797 [http-nio-8081-exec-2] ERROR c.example.trading.application.TradeResponseHelper - 订单[BUY0000000000005]事务失败
  ...
  2026-02-19 15:39:25.820 [http-nio-8081-exec-2] ERROR com.example.trading.application.ExchangeService - 订单[BUY0000000000005]事务失败
  
  ```

---
#### 案例1.5.1：测试应用崩溃重启自动加载数据重新撮合

**数据库中插入两条数据**
```
1	SEL0000000000004	SH00000002	XSHG	600030	S	100	100	20.00	3	2026-02-19 16:17:03	1
2	BUY0000000000005	SH00000001	XSHG	600030	B	100	100	20.00	0	2026-02-19 16:17:47	0
```

**重启应用**
- 自动加载数据
- 自动重启验证和撮合流程
- 更新order和trade数据库
```
- order数据：
0	SEL0000000000004	SH00000002	XSHG	600030	S	0	100	20.00	5	2026-02-19 16:17:03	2
1	BUY0000000000005	SH00000001	XSHG	600030	B	0	100	20.00	5	2026-02-19 16:17:47	2

- trade新增：
1	48939129E966	BUY0000000000005	SEL0000000000004	100	20.00	2026-02-19 16:23:11	XSHG	600030	SH00000001	SH00000002
```