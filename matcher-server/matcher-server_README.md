# **C++ Matching Engine (Matcher Server)**

## **1\. 项目简介**

这是核心撮合引擎模块，负责维护订单簿（OrderBook）、执行撮合逻辑以及生成交易回报。

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
* mysql-connector-cpp: 数据库连接 (可选)

## **4\. 构建步骤 (Build Instructions)**

### **第一步：配置 (Configure)**

CMake 会自动检测 vcpkg 并安装所有依赖。

**Windows (PowerShell):**

\# 假设环境变量 VCPKG\_ROOT 已设置，否则添加: \-DCMAKE\_TOOLCHAIN\_FILE="C:/path/to/vcpkg/scripts/buildsystems/vcpkg.cmake"  
cmake \-B build \-S . \-DCMAKE\_TOOLCHAIN\_FILE="$env:VCPKG\_ROOT/scripts/buildsystems/vcpkg.cmake"

**Linux / macOS (Bash):**

cmake \-B build \-S . \-DCMAKE\_TOOLCHAIN\_FILE="$VCPKG\_ROOT/scripts/buildsystems/vcpkg.cmake" \-DCMAKE\_BUILD\_TYPE=Release

### **第二步：编译 (Build)**

cmake \--build build \--config Release

### **第三步：运行测试 (Test)**

cd build  
ctest \-C Release \--output-on-failure

## **5\. 项目结构 (Project Structure)**

**标注T的为必须的，标注F的为尚未明确是否存在的**

matcher-server/  
├── vcpkg.json              \#T 依赖清单 (自动管理依赖版本)  
├── CMakeLists.txt          \#T 主构建脚本  
├── src/                    \#T 源代码  
│   ├── main.cpp            \#T 入口点  
│   ├── core/               \#T 核心逻辑 (OrderBook, MatchingEngine)  
│   ├── ipc/                \#T 进程间通信接口 (如使用共享内存或消息队列)
│   ├── network/            \#F 网络层 (Gateway Interface)  
│   └── util/               \#F工具类 (Logger, Time)  
├── include/                \#T 头文件  
│   └── matcher/            \#F 公开头文件  

├── tests/                  \#T GoogleTest 测试用例  
└── benchmarks/             \#T 性能基准测试

## **6\. 开发规范 (Coding Standard)**

* **C++ 标准**: C++17 (为了兼顾性能特性与库兼容性)  
* **编码格式**: 遵循项目根目录 .clang-format  
* **禁止事项**:  
  * 禁止在热路径（Hot Path）中使用 new/malloc (使用内存池)。  
  * 禁止在撮合线程中打印同步日志 (使用 spdlog 异步模式)。  
  * 禁止使用 C++ iostream (cout/cin) 进行 I/O (不仅慢且非线程安全)。