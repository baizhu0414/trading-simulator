# **C++ Matching Engine (Matcher Server)**

## **1\. 项目简介**

这是核心撮合引擎模块，负责维护订单簿（OrderBook）、执行撮合逻辑以及生成交易回报。（订单对应的数据库由java层管理，C++层仅处理内存中的订单簿和撮合逻辑）

设计目标为 **低延迟（Low Latency）**、**高吞吐（High Throughput）** 且 **跨平台（Cross-Platform）**。

## **2\. 环境要求 (Prerequisites)**

### **通用要求**

* **CMake**: 3.20 或更高版本  
* **Git**: 用于版本控制  
* **vcpkg**: C++ 包管理器 (必须配置环境变量 VCPKG\_ROOT)

### **Windows 开发环境 (推荐)**

* **IDE**: Visual Studio 2022 或 CLion  
* **编译器**: MSVC v142+ 或 Clang-CL  
* **Shell**: PowerShell 7+

### **Linux / macOS 开发环境 (生产环境标准)**

* **编译器**: GCC 10+ 或 Clang 12+  
* **构建工具**: Ninja (推荐) 或 Make  
  \# Ubuntu/Debian 示例  
  sudo apt-get install build-essential cmake ninja-build curl zip unzip tar

## **3\. 依赖管理 (Manifest Mode)**

本项目使用 **vcpkg manifest 模式**。

你**不需要**手动运行 vcpkg install。依赖项已定义在 vcpkg.json 中：

* nlohmann-json: JSON 序列化/反序列化  
* spdlog: 高性能异步日志  
* gtest: 单元测试框架  
* libmysql: MySQL C客户端库 （仅用于测试，生产环境中由java层管理）
* cpp-httplib: 轻量级 C++ HTTP 库 (用于与 Java 层通信)

## **4\. 构建步骤 (Build Instructions)**

### **第一步：配置 (Configure)**

CMake 会自动检测 vcpkg 并安装所有依赖。

**Windows (PowerShell):**

*本部分需要根据开发者的环境调整，这里建议在mathcer-server文件夹下执行cmake,避免build文件夹出现在根目录*

*VCPKG_INSTALLED_DIR和CMAKE_PREFIX_PATH需要手动正确设置,开发者可能需要手动设置settings.json避免vscode的问题栏中出现的CMakeLists.txt飘红（可正常执行cmake），这里可能是vscode的cmake的配置导致的*


\# 假设环境变量 VCPKG\_ROOT 已设置，否则添加: \-DCMAKE\_TOOLCHAIN\_FILE="C:/path/to/vcpkg/scripts/buildsystems/vcpkg.cmake"  
cmake \-B build \-S . \-DCMAKE\_TOOLCHAIN\_FILE="$env:VCPKG\_ROOT/scripts/buildsystems/vcpkg.cmake"

**Linux / macOS (Bash):**

cmake \-B build \-S . \-DCMAKE\_TOOLCHAIN\_FILE="$VCPKG\_ROOT/scripts/buildsystems/vcpkg.cmake" \-DCMAKE\_BUILD\_TYPE=Release

### **第二步：编译 (Build)**

cmake \--build build \--config Release

### **快速编译 (Quick Build - 适用于已配置的项目)**

如果项目已经配置过（build目录已存在），可以使用以下简化命令：

**Windows (PowerShell):**
```powershell
# 进入项目目录
cd matcher-server

# 快速编译（Debug配置，适合开发调试）
cmake --build build --config Debug

# 或者使用Release配置（适合性能测试）
cmake --build build --config Release
```

**Linux / macOS (Bash):**
```bash
# 进入项目目录
cd matcher-server

# 快速编译
cmake --build build --config Release
```

**注意：**
- 快速编译假设项目已通过第一步配置
- Debug配置生成调试信息，适合代码审查和bug检查
- Release配置优化性能，适合生产环境测试
- 可执行文件位置：`build/Debug/`（Debug）或 `build/Release/`（Release）

### **第三步：运行测试 (Test)**

cd build  
ctest -C Release --output-on-failure

### **第四步：运行程序 (Run)**

编译成功后,可执行文件位于 `build/Release/matcher_server.exe` (Windows) 或 `build/matcher_server` (Linux/macOS)。

**测试模式 (MySQL 直连):**

```bash
# Windows
build\Release\matcher_server.exe --mode TEST --mysql mysqlx://root:password@localhost:3306/trading

# Linux/macOS
./build/matcher_server --mode TEST --mysql mysqlx://root:password@localhost:3306/trading
```

**生产模式 (远程代理):**

```bash
# Windows
build\Release\matcher_server.exe --mode PRODUCTION --ipc http:9001:localhost:8081

# Linux/macOS
./build/matcher_server --mode PRODUCTION --ipc http:9001:localhost:8081
```

**命令行参数说明:**

- `--mode <TEST|PRODUCTION>`: 运行模式
  - `TEST`: 使用 MySQLPersistence 直连本地数据库
  - `PRODUCTION`: 使用 ProxyPersistence 通过 Java 层访问数据库
- `--mysql <url>`: MySQL 连接字符串 (TEST 模式必需)
- `--ipc <config>`: IPC 配置,格式 `http:port:javaHost:javaPort`
- `--verbose, -v`: 启用详细日志输出
- `--help, -h`: 显示帮助信息

**注意**: 当前版本需要撮合引擎实现后才能完整运行。

## **5\. 项目结构 (Project Structure)**

```
matcher-server/
├── vcpkg.json              # 依赖清单 (自动管理依赖版本)
├── CMakeLists.txt          # 主构建脚本
├── matcher-server_README.md # 本文档
├── docs/                   # 设计文档
│   └── matcher-server_docs_design_v1.0.md
├── include/                # 头文件
│   ├── core/
│   │   ├── IMatchingEngine.h    # 撮合引擎接口 (已定义)
│   │   └── OrderBook.h          # 订单簿接口 (待实现)
│   ├── ipc/
│   │   ├── IPCServer.h          # IPC 抽象接口
│   │   └── HttpIPCServer.h      # HTTP IPC 实现
│   ├── model/
│   │   ├── Order.h              # 订单数据模型
│   │   └── Trade.h              # 成交数据模型
│   ├── persistence/
│   │   ├── IPersistence.h       # 持久化抽象接口
│   │   ├── MySQLPersistence.h   # MySQL 直连实现
│   │   └── ProxyPersistence.h   # 远程代理实现
│   └── util/
│       └── Logger.h             # 日志接口 (待实现)
├── src/                    # 源代码
│   ├── main.cpp            # 入口点
│   ├── ipc/
│   │   ├── HttpIPCServer.cpp    # HTTP IPC 实现
│   │   └── StdioIPCServer.cpp   # Stdio IPC (已废弃)
│   └── persistence/
│       ├── MySQLPersistence.cpp
│       └── ProxyPersistence.cpp
├── tests/                  # GoogleTest 测试用例 (待添加)
└── benchmarks/             # 性能基准测试 (待添加)
```

**当前状态:**
- ✅ 基础设施层完成 (IPC, Persistence, Model)
- ✅ 撮合引擎接口已定义 (IMatchingEngine)
- ⏳ 撮合引擎实现待完成 (MatchingEngine, OrderBook)
- ⏳ 日志系统待实现 (Logger)

## **6\. 开发规范 (Coding Standard)**

* **C++ 标准**: C++17 (为了兼顾性能特性与库兼容性)  
* **编码格式**: 遵循项目根目录 .clang-format  
* **禁止事项**:  
  * 禁止在热路径（Hot Path）中使用 new/malloc (使用内存池)。  
  * 禁止在撮合线程中打印同步日志 (使用 spdlog 异步模式)。  
  * 禁止使用 C++ iostream (cout/cin) 进行 I/O (不仅慢且非线程安全)。