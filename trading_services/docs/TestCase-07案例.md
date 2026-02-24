## 案例 7.1 基础功能测试

| 测试子项 | 对应需求点 |
|------|------------|
| 1.1 | 基础撤单-订单创建后立即撤销 |
| 1.2 | 撤单幂等-同一订单重复撤销 |
| 1.3 | 撤单后撮合隔离-已撤销订单不参与撮合 |
| 1.4 | 部分成交后撤单-剩余部分不参与撮合 |

### 案例 7.1.1：基础撤单-订单创建后立即撤销 √
> 测试点：验证订单创建后立即撤销的全流程，包括订单状态更新、OrderBook 清理、风控缓存清理、Validator 缓存标记
#### 前置条件
1.  零股开关配置：`matching.zero-share-enable=false`
2.  系统订单簿中无股票`600030`的存量挂单
3.  无对敲风控风险

#### 输入1 - 创建订单A（买单）
```json
{
  "clOrderId": "ORD2026022400001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 10.5,
  "qty": 1000,
  "shareholderId": "test000001"
}
```

#### 输入2 - 撤销订单A
```json
{
  "origClOrderId": "ORD2026022400001",
  "market": "XSHG",
  "securityId": "600030",
  "shareholderId": "test000001",
  "side": "B"
}
```

#### 预期结果
1.  **订单A状态**：数据库中 `status=5=CANCELED`，`qty=1000`（保持不变）
2.  **OrderBook**：订单A不在 `600030` 买单队列中
3.  **风控缓存**：`SelfTradeChecker` 中无 `test000001_600030`
4.  **Validator缓存**：`CancelValidator` 中已标记 `ORD2026022400001` 为已撤销
    ```json
    {
        "code": "3000",
        "msg": "撤单成功",
        "clOrderId": null,
        "origClOrderId": "ORD2026022400001",
        "market": "XSHG",
        "securityId": "600030",
        "shareholderId": "test000001",
        "side": "B",
        "qty": 1000,
        "price": 10.5,
        "cumQty": null,
        "canceledQty": null,
        "orderStatus": "已撤销",
        "leavesQty": null
    }
    ```

---

### 案例 7.1.2：撤单幂等-同一订单重复撤销 √
> 测试点：验证同一订单重复撤销时，第二次请求直接被拦截（Validator 缓存命中），不重复操作数据库和订单簿
#### 前置条件
1.  订单A已按【案例1.1】撤销成功
2.  Validator 缓存中已有 `ORD2026022400001`

#### 输入 - 再次撤销订单A
```json
{
  "origClOrderId": "ORD2026022400001",
  "market": "XSHG",
  "securityId": "600030",
  "shareholderId": "test000001",
  "side": "B"
}
```

#### 预期结果
1.  **响应**：返回 `ORDER_NOT_CANCELABLE` 错误
```json
{
	"code": "5004",
	"msg": "订单状态不可撤销",
	"clOrderId": "ORD2026022400001",
	"origClOrderId": "ORD2026022400001",
	"rejectCode": 5004,
	"rejectText": "订单状态不可撤销"
}
```

---

### 案例 7.1.3：撤单后撮合隔离-已撤销订单不参与撮合 √
> 测试点：验证订单A撤销后，创建能撮合的对手订单B，B不会与A撮合，直接进入订单簿
#### 前置条件
1.  订单A已按【案例1.1】撤销成功
2.  订单簿中无 `600030` 的其他挂单

#### 输入 - 创建对手订单B（卖单，价格与A匹配）
```json
{
  "clOrderId": "ORD2026022400002",
  "market": "XSHG",
  "securityId": "600030",
  "side": "S",
  "price": 10.5,
  "qty": 100,
  "shareholderId": "test000002"
}
```

#### 预期结果
1.  **订单B状态**：`status=NOT_FILLED`（未成交）
```json
{
	"code": "2000",
	"msg": "订单已确认（未成交）",
	"clOrderId": "ORD2026022400002",
	"market": "XSHG",
	"securityId": null,
	"side": "S",
	"qty": 100,
	"price": 10.4,
	"shareholderId": "test000002",
	"orderStatus": "无对手单未成交"
}
```
3.  **成交记录**：无任何 Trade 记录生成
4.  **订单簿**：订单B在 `600030` 卖单队列中，价格10.5

---

### 案例 7.1.4：部分成交后撤单-剩余部分不参与撮合 √
> 测试点：验证订单C部分成交后撤销，剩余部分不参与后续撮合
#### 前置条件
1.  零股开关配置：`matching.zero-share-enable=false`
2.  订单簿中无股票`600030`的存量挂单

#### 输入1 - 创建订单C（买单1000,与7.1.3订单B部分成交）
```json
{
  "clOrderId": "ORD2026022400003",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 10.5,
  "qty": 1000,
  "shareholderId": "test000003"
}
```
返回：
```json
{
	"code": "0000",
	"msg": "成交成功（部分/完全成交时返回）",
	"clOrderId": "ORD2026022400003",
	"market": "XSHG",
	"securityId": "600030",
	"side": "B",
	"orderQty": 1000,
	"orderPrice": 10.5,
	"shareholderId": "test000003",
	"orderStatus": "部分成交",
	"tradeResponses": [
		{
			"execId": "94352972E321",
			"execQty": 100,
			"execPrice": 10.45,
			"execTime": "2026-02-24T22:32:09.7238355",
			"orderStatus": "完全成交"
		}
	]
}
```


**预期中间状态**：
- 订单C：`status=PART_FILLED`，`qty=700`
- 订单B：`status=FULL_FILLED`，`qty=0`
- 生成1笔 Trade 记录，成交数量300

#### 输入3 - 撤销订单C
```json
{
  "origClOrderId": "ORD2026022400003",
  "market": "XSHG",
  "securityId": "600030",
  "shareholderId": "test000003",
  "side": "B"
}
```

返回：
```json
{
	"code": "3000",
	"msg": "撤单成功",
	"clOrderId": null,
	"origClOrderId": "ORD2026022400003",
	"market": "XSHG",
	"securityId": "600030",
	"shareholderId": "test000003",
	"side": "B",
	"qty": 1000,
	"price": 10.5,
	"cumQty": null,
	"canceledQty": null,
	"orderStatus": "已撤销",
	"leavesQty": null
}
```

#### 输入4 - 创建订单D（卖单700，价格与C匹配）
```json
{
  "clOrderId": "ORD2026022400005",
  "market": "XSHG",
  "securityId": "600030",
  "side": "S",
  "price": 10.5,
  "qty": 700,
  "shareholderId": "test000005"
}
```

返回：
```json
{
	"code": "2000",
	"msg": "订单已确认（未成交）",
	"clOrderId": "ORD2026022400005",
	"market": "XSHG",
	"securityId": null,
	"side": "S",
	"qty": 700,
	"price": 10.5,
	"shareholderId": "test000005",
	"orderStatus": "无对手单未成交"
}
```

#### 最终预期结果
见流程中，多个结果均正确才可。