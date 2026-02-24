## 第6类：复杂案例

| 测试子项 | 对应需求点 |
|------|------------|
| 5.1  | 核心规则-单笔订单多次成交（一个订单匹配多笔对手单） |

## 案例 6.1：核心规则-单笔订单多次成交（一个订单匹配多笔对手单）
> 测试点：验证单笔大额委托订单，可分多次与多笔不同对手方订单完成撮合，生成多笔独立成交记录，最终完全成交，全流程订单状态、成交数据、订单簿状态一致性
### 前置条件
1.  零股开关配置：`matching.zero-share-enable=false`
2.  系统订单簿中无股票`830881`的存量挂单
3.  所有卖单与买单为不同股东号，无对敲风控风险

### 输入1 - 先提交3笔独立卖单（分批次挂单，同价格同股票）
#### 卖单1：
```json
{
  "clOrderId": "BUY_LOW000000022",
  "market": "XSHG", // 不同交易所，不触发撮合
  "securityId": "600030",
  "side": "B",
  "price": 10.4,
  "qty": 100,
  "shareholderId": "SH00000022"
}
```
#### 卖单2：
```json
{
  "clOrderId": "SEL0000000000028",
  "market": "BJSE",
  "securityId": "830881",
  "side": "S",
  "price": 8.0,
  "qty": 100,
  "shareholderId": "SH00000028"
}
```
#### 卖单3：
```json
{
  "clOrderId": "SEL0000000000029",
  "market": "BJSE",
  "securityId": "830881",
  "side": "S",
  "price": 8.0,
  "qty": 100,
  "shareholderId": "SH00000029"
}
```

### 输入2 - 后提交大额买单（委托总量覆盖3笔卖单）
```json
{
  "clOrderId": "BUY0000000000030",
  "market": "BJSE",
  "securityId": "830881",
  "side": "B",
  "price": 8.0,
  "qty": 400,
  "shareholderId": "SH00000030"
}
```

### 预期结果
```json
{
	"code": "0000",
	"msg": "成交成功（部分/完全成交时返回）",
	"clOrderId": "BUY0000000000030",
	"market": "BJSE",
	"securityId": "830881",
	"side": "B",
	"orderQty": 400,
	"orderPrice": 8,
	"shareholderId": "SH00000030",
	"orderStatus": "部分成交",
	"tradeResponses": [
		{
			"execId": "528151282F92",
			"execQty": 100,
			"execPrice": 8,
			"execTime": "2026-02-20T03:09:11.2847505",
			"orderStatus": "完全成交"
		},
		{
			"execId": "52815128DFD8",
			"execQty": 100,
			"execPrice": 8,
			"execTime": "2026-02-20T03:09:11.2847505",
			"orderStatus": "完全成交"
		}
	]
}
```

---