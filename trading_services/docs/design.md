# 交易撮合模拟系统设计文档 v0.2
## 1. 系统架构
### 1.1 整体架构
系统采用分层架构设计，核心流程为：订单JSON输入 → 基础校验 → 对敲风控 → 撮合匹配 → 回报JSON输出，行情、撤单、数据分析为可插拔模块，不影响核心流程。

### 1.2 架构图
```
trading-simulator/
├── README.md # 必看
├── pom.xml
│
├── docs/ # 必看
│   ├── data/
│   ├── monitoring/ # Grafana监控体系设计文档（指标设计、表盘设计和导入、可观测性建设文档、结果展示）
│   ├── sql/
│   ├── test-cases/ # 测试案例TestCase
│   ├── *.sh # Linux服务启停脚本
│   ├── Linux部署教程
│   └── design.md # 项目设计文档、数据库设计
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/trading/
│   │   │
│   │   │       ├── TradingSimulatorApplication.java
│   │   │
│   │   │       ├── controller/                 # 接口层（UI / API）
│   │   │       │   └── TradingController.java
│   │   │
│   │   │       ├── application/                # 应用层（流程编排）
│   │   │       │   ├── ExchangeService.java
│   │   │       │   ├── AsyncPersistService,OrderReconciliationService,PersistRetryTaskJob等
│   │   │       │   └── CancelService.java
│   │   │
│   │   │       ├── domain/                     # ⭐ 核心领域层（重点）
│   │   │       │
│   │   │       │   ├── model/                  # 领域模型
│   │   │       │   │   ├── Order.java
│   │   │       │   │   ├── CancelOrder.java
│   │   │       │   │   └── Trade.java
│   │   │       │
│   │   │       │   ├── engine/                 # 撮合引擎
│   │   │       │   │   ├── MatchingEngine.java
│   │   │       │   │   ├── OrderBook.java
│   │   │       │   │   └── PriceGenerator.java
│   │   │       │
│   │   │       │   ├── risk/                   # 风控规则
│   │   │       │   │   └── SelfTradeChecker.java
│   │   │       │
│   │   │       │   └── validation/             # 领域校验
│   │   │       │       ├── OrderValidator.java
│   │   │       │       └── CancelValidator.java
│   │   │
│   │   │       ├── mapper/                 # 数据访问层
│   │   │       │   ├── OrderMapper.java
│   │   │       │   └── TradeMapper.java
│   │   │
│   │   │       ├── infrastructure/             # 技术细节
│   │   │       │   ├── persistence/
│   │   │       │   │   ├── OrderStore.java
│   │   │       │   │   └── TradeStore.java
│   │   │
│   │   │       ├── common/
│   │   │       │   ├── Constants.java
│   │   │       │   ├── enums/
│   │   │       │   │   ├── SideEnum.java
│   │   │       │   │   ├── ErrorCodeEnum.java
│   │   │       │   │   ├── ResponseCodeEnum.java
│   │   │       │   │   └── OrderStatusEnum.java # 订单状态流转
│   │   │       │   └── typehandler # Enum-MyBatis类型转换
│   │   │       ├── config/ # 线程池、定时任务池配置
│   │   │       └── util/
│   │   │           ├── ExecIdGenUtils.java # 交易记录ID生成
│   │   │           └── JsonUtils.java
│   │   │
│   │   └── resources/
│   │       ├── mapper/ # MyBatis配置
│   │       ├── application.yml
│   │       └── logback.xml # 过程日志持久化、定期清除
│   │
│   └── test/
│       └── java/
│           └── com/example/trading/
│               ├── domain/
│               │   └── engine/
│               ├── RedisTest # 数据库插入失败重试缓存
│               └── application/
│
└── .gitignore
```


## 2. [核心数据结构](../src/main/java/com/example/trading/domain/model)
