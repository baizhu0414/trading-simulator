# 交易撮合模拟系统设计文档 v0.1
## 1. 系统架构
### 1.1 整体架构
系统采用分层架构设计，核心流程为：订单JSON输入 → 基础校验 → 对敲风控 → 撮合匹配 → 回报JSON输出，行情、撤单、数据分析为可插拔模块，不影响核心流程。

### 1.2 架构图


## 2. 核心模块说明
### 2.1 Exchange Gateway（接口层）
- 实现类：TradingController
- 功能：接收外部JSON订单，路由到ExchangeService处理，返回JSON回报。

### 2.2 OrderValidator（基础校验）
- 实现类：OrderValidator
- 功能：校验订单字段完整性、市场合法性、买卖方向合法性、数量/价格合法性，无业务含义。

### 2.3 Risk Controller（对敲风控）
- 实现类：SelfTradeChecker
- 功能：基于股东号+股票代码缓存，检测同一股东号的自买自卖行为，拦截违规订单。

### 2.4 Matching Engine（撮合引擎）
- 核心类：MatchingEngine、OrderBook、PriceGenerator
- 功能：维护股票订单簿，实现价格优先撮合规则，生成成交价格，处理零股成交。

### 2.5 Trade Reporter（交易回报）
- 实现方式：ExchangeService内置回报构建逻辑
- 功能：根据处理结果生成成功/拒绝回报，统一JSON格式输出。

## 3. 核心数据结构
### 3.1 Order（订单）
| 字段名         | 类型       | 说明                     |
|----------------|------------|--------------------------|
| clOrderId      | String     | 订单唯一编号             |
| market         | String     | 交易市场（XSHG/XSHE/BJSE）|
| securityId     | String     | 股票代码                 |
| side           | SideEnum   | 买卖方向（B/S）|
| qty            | Integer    | 订单数量                 |
| price          | Double     | 订单价格                 |
| shareholderId  | String     | 股东号                   |
| status         | OrderStatusEnum | 订单状态           |
| timestamp      | Long       | 提交时间戳               |

## 4. 核心流程
1. 订单接收：客户端POST JSON订单到/api/trading/order接口；
2. 基础校验：OrderValidator校验订单合法性，失败则返回非法回报；
3. 风控检查：SelfTradeChecker检测对敲风险，失败则返回非法回报；
4. 撮合匹配：MatchingEngine将订单加入订单簿，尝试与对手方订单撮合；
5. 回报生成：根据处理结果返回成功/成交/非法回报JSON。