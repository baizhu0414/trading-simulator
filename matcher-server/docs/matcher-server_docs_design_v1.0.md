# **C++ 撮合引擎 (Matcher Server) 详细设计文档 v1.0**

## **1\. 架构概览**

本项目采用 **核心逻辑与基础设施分离** 的设计原则。

* **Core Layer (核心层)**: 包含纯粹的金融交易逻辑（订单簿、撮合算法），不依赖具体数据库或网络库。  
* **Infrastructure Layer (基础层)**: 负责日志、IPC 通信、数据持久化的具体实现。  
* **Interface Layer (接口层)**: 定义核心层与外界交互的抽象契约。

## **2\. 模块详细设计**

### **2.1 日志管理模块 (Logger)**

**需求分析**: 需要将不同维度的日志（订单流、系统调用、数据库操作）写入不同的物理文件，且必须保证非阻塞（Async），不影响撮合性能。

**类设计**: LogManager (单例模式/静态工具类)

**文件路径**: src/util/Logger.hpp / src/util/Logger.cpp

**方法设计**:

| 方法签名 | 描述 | 实现细节 |
| :---- | :---- | :---- |
| static void init(const Config& config) | 初始化日志系统 | 配置 spdlog 的异步模式 (8k+ 队列)，创建各个 Sink (OrderSink, DbSink, TraceSink)。 |
| static void logOrder(const Order& order, const char\* msg) | 记录订单生命周期 | 写入 logs/orders.log。包含 clOrderId, price, qty 等关键字段。 |
| static void logDB(const char\* sql, const char\* status) | 记录持久化操作 | 写入 logs/db.log。记录 SQL 语句或 IPC 发送状态。 |
| static void logSys(const char\* func, const char\* msg) | 记录系统调用 | 写入 logs/system.log。用于 Trace 函数耗时和异常。 |

**核心代码示意**:

// 在 init 中  
auto order\_sink \= std::make\_shared\<spdlog::sinks::basic\_file\_sink\_mt\>("logs/orders.log");  
auto db\_sink \= std::make\_shared\<spdlog::sinks::basic\_file\_sink\_mt\>("logs/db.log");

// 注册为不同的 logger  
spdlog::register("order\_logger", std::make\_shared\<spdlog::async\_logger\>("order", order\_sink, ...));  
spdlog::register("db\_logger", std::make\_shared\<spdlog::async\_logger\>("db", db\_sink, ...));

### **2.2 IPC 通信模块 (IPCManager)**

**需求分析**: 负责作为“网关”，接收 Java 层传来的 JSON 数据，并将撮合结果返回。

**类设计**: IPCServer

**文件路径**: src/ipc/IPCServer.hpp

**方法设计**:

| 方法签名 | 描述 | 输入/输出 |
| :---- | :---- | :---- |
| void start() | 启动监听循环 | 从 Stdin (标准输入) 或 TCP Socket 读取数据。 |
| Order parseOrder(const std::string& jsonStr) | 协议解析 | 将 JSON 字符串反序列化为 C++ Order 结构体。 |
| void sendExecutionReport(const ExecutionReport& report) | 发送成交回报 | 将成交结果序列化为 JSON，通过 Stdout 或 Socket 发送回 Java。 |
| void sendCommand(const std::string& type, const json& payload) | 通用指令发送 | 用于向 Java 发送非回报类请求（如持久化请求）。 |

**工作流**:

1. main 函数启动 IPCServer.start() 在主线程。  
2. 收到数据 \-\> 解析 \-\> 调用 MatchingEngine.submitOrder()。  
3. MatchingEngine 产生结果 \-\> 调用 IPCServer.sendExecutionReport()。

### **2.3 数据持久化模块 (Strategy Pattern)**

**需求分析**:

* **开发/测试环境**: C++ 直接连接本地 MySQL，方便单元测试和断点调试。  
* **生产环境**: C++ **不** 连接数据库，而是将数据变更请求发回给 Java 层（由 Java 统一管理事务和数据库连接池）。

这是典型的 **策略模式 (Strategy Pattern)** 应用场景。

**文件路径**: src/persistence/

#### **2.3.1 抽象接口 (IPersistence)**

所有核心业务只依赖这个接口。

class IPersistence {  
public:  
    virtual \~IPersistence() \= default;  
    virtual void saveTrade(const Trade& trade) \= 0;  
    virtual void updateOrder(const Order& order) \= 0;  
    virtual std::vector\<Order\> loadUnfinishedOrders(const std::string& securityId) \= 0;  
};

#### **2.3.2 实现类 A: 本地直连 (MySQLPersistence)**

用于单元测试 (Unit Test).

| 方法实现 | 逻辑 |
| :---- | :---- |
| saveTrade | 使用 mysql-connector-cpp 执行 INSERT INTO trades ... |
| updateOrder | 执行 UPDATE orders SET status \= ... |

#### **2.3.3 实现类 B: 远程代理 (ProxyPersistence)**

用于生产环境 (Production).

| 方法实现 | 逻辑 |
| :---- | :---- |
| saveTrade | 构建 JSON {type: "SAVE\_TRADE", data: trade}, 调用 IPCServer::sendCommand 发送给 Java。 |
| updateOrder | 构建 JSON {type: "UPDATE\_ORDER", data: order}, 发送给 Java。 |

**切换方式 (在 main.cpp 中)**:

std::unique\_ptr\<IPersistence\> db;  
if (config.mode \== "TEST") {  
    db \= std::make\_unique\<MySQLPersistence\>(config.mysql\_url);  
} else {  
    db \= std::make\_unique\<ProxyPersistence\>(ipc\_server); // 注入 IPC 实例  
}  
// 将 db 注入到引擎中  
MatchingEngine engine(std::move(db));

### **2.4 核心撮合模块 (MatchingEngine)**

**需求分析**: 维护订单簿，执行撮合逻辑。它不关心数据存哪里，也不关心数据怎么来的。

**类设计**: MatchingEngine 和 OrderBook

**文件路径**: src/core/

**方法设计**:

**A. OrderBook (单只股票的账本)**

| 方法签名 | 描述 |

| :--- | :--- |

| void addOrder(Order ptr) | 将订单插入买盘或卖盘（使用 std::map 或 std::priority\_queue）。 |

| MatchResult match() | **核心算法**。循环比对买一和卖一，生成成交列表。 |

| void cancel(clOrderId) | 标记订单为撤销。 |

**B. MatchingEngine (总控)**

| 方法签名 | 描述 |

| :--- | :--- |

| MatchingEngine(unique\_ptr\<IPersistence\> db) | **构造注入**。接收具体的数据库实现类。 |

| void onOrderInput(Order order) | 入口函数。1. 存库/发IPC(Received) 2\. 路由到对应 OrderBook 3\. 触发 match。 |

| void processMatchResult(MatchResult res) | 处理撮合结果。1. 调用 db-\>saveTrade 2\. 调用 IPC-\>sendExecutionReport。 |

## **3\. 类关系图 (UML 概念)**

classDiagram  
    class Main {  
        \+init()  
    }

    class IPCServer {  
        \+listen()  
        \+send()  
    }

    class MatchingEngine {  
        \-map\<string, OrderBook\> books  
        \-IPersistence\* db  
        \+submitOrder()  
    }

    class IPersistence {  
        \<\<Interface\>\>  
        \+saveTrade()  
    }

    class MySQLPersistence {  
        \+saveTrade()  
        \-Connection conn  
    }

    class ProxyPersistence {  
        \+saveTrade()  
        \-IPCServer\* ipc  
    }

    Main \--\> IPCServer : Creates  
    Main \--\> IPersistence : Factory Creates  
    Main \--\> MatchingEngine : Injects DB & IPC  
    MatchingEngine o-- IPersistence : Uses  
    MatchingEngine o-- OrderBook : Manages  
    ProxyPersistence \--\> IPCServer : Uses for transport  
    MySQLPersistence ..\> MySQL\_Lib : Depends

## **4\. 关键交互流程**

### **场景：生产环境收到一个新订单**

1. **Java 层**: 收到 HTTP 请求 \-\> 校验 \-\> 风控通过 \-\> **JSON 序列化** \-\> 发送给 C++ 进程。  
2. **C++ IPC 层**: IPCServer 读取 STDIN \-\> 解析 JSON \-\> 创建 Order 对象。  
3. **C++ 核心层**: 调用 MatchingEngine::onOrderInput(order)。  
4. **持久化 (Proxy)**: 引擎调用 db-\>updateOrder(order)。  
   * 由于是 ProxyPersistence，它将 {type: "ORDER\_PERSIST", ...} JSON 发回 Java。  
   * Java 收到后异步写入 MySQL。  
5. **撮合**: 引擎调用 OrderBook::match()，发现成交。  
6. **成交处理**:  
   * 引擎生成 Trade 对象。  
   * 调用 db-\>saveTrade(trade) \-\> ProxyPersistence 发送 {type: "SAVE\_TRADE", ...} 给 Java。  
   * 调用 IPCServer::sendExecutionReport \-\> 发送 {type: "EXECUTION", ...} 给 Java。  
7. **日志**: 全程调用 Logger::logOrder(...) 记录到本地文件 orders.log。

## **5\. 下一步开发计划 (Task List)**

1. **基础设施搭建**:  
   * 引入 spdlog 并实现 Logger 类。  
   * 定义 Order 和 Trade 的 C++ 结构体 (struct)。  
2. **核心逻辑实现**:  
   * 实现 OrderBook 的添加与基础撮合循环。  
3. **持久化层实现**:  
   * 定义 IPersistence 纯虚基类。  
   * 优先实现 MySQLPersistence (方便你在本地写单元测试，不依赖 Java)。  
4. **集成**:  
   * 编写 main.cpp，根据参数选择模式。