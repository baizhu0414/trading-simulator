# **C++ Matching Engine (Matcher Server)**

## **1\. 项目简介**

这是核心撮合引擎模块，负责维护订单簿（OrderBook）、执行撮合逻辑以及生成交易回报。

设计目标为 **低延迟（Low Latency）**、**高吞吐（High Throughput）** 且 **跨平台（Cross-Platform）**。

**当前分支**: `feature/matcher-server-develop`

**实现状态**:
- ✅ 撮合引擎核心逻辑 (MatchingEngine)
- ✅ 订单簿实现 (OrderBook)
- ✅ 持久化层 (MySQLPersistence, ProxyPersistence)
- ✅ IPC 通信 (HttpIPCServer)
- ✅ 日志系统 (Logger)
- ✅ 数据模型 (Order, Trade)

## **2\. 环境要求 (Prerequisites)**

### **通用要求**

* **CMake**: 3.20 或更高版本  
* **Git**: 用于版本控制  
* **vcpkg**: C++ 包管理器 (必须配置环境变量 VCPKG\_ROOT)

### **Windows 开发环境**

* **IDE**: Visual Studio 2022 或 CLion  
* **编译器**: MSVC v142+ 或 Clang-CL  
* **Shell**: PowerShell 7+

### **Linux 生产环境**

* **编译器**: GCC 10+ 或 Clang 12+  
* **构建工具**: Ninja (推荐) 或 Make

```bash
# Ubuntu/Debian 安装依赖
sudo apt-get install build-essential cmake ninja-build curl zip unzip tar
```

## **3\. 依赖管理 (Manifest Mode)**

本项目使用 **vcpkg manifest 模式**，依赖项已定义在 `vcpkg.json` 中：

* **nlohmann-json**: JSON 序列化/反序列化  
* **spdlog**: 高性能异步日志  
* **gtest**: 单元测试框架  
* **libmysql**: MySQL C客户端库
* **cpp-httplib**: 轻量级 C++ HTTP 库

CMake 配置时会自动安装所有依赖，**无需手动运行** `vcpkg install`。

## **4\. 构建步骤 (Build Instructions)**

### **开发环境快速构建**

**Windows (PowerShell):**

```powershell
cd matcher-server
cmake -B build -S . -DCMAKE_TOOLCHAIN_FILE="$env:VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake"
cmake --build build --config Debug
```

**Linux / macOS:**

```bash
cd matcher-server
cmake -B build -S . -DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

### **生产环境编译**

```bash
cd matcher-server
cmake -B build -S . \
  -DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j$(nproc)
```

## **5\. 运行程序**

### **命令行参数**

```bash
./matcher_server [options]

Options:
  --mode <TEST|PRODUCTION>   运行模式 (默认: TEST)
  --mysql <url>              MySQL 连接字符串 (TEST 模式必需)
  --ipc <config>             IPC 配置 (格式: http:port:javaHost:javaPort)
  --verbose, -v              启用详细日志
  --help, -h                 显示帮助
```

### **测试模式 (开发调试)**

```bash
# Windows
build\Debug\matcher_server.exe --mode TEST --mysql mysqlx://root:password@localhost:3306/trading

# Linux
./build/matcher_server --mode TEST --mysql mysqlx://root:password@localhost:3306/trading
```

### **生产模式 (推荐)**

```bash
./build/matcher_server --mode PRODUCTION --ipc http:9001:localhost:8081
```

**IPC 配置说明**: `http:本地监听端口:Java服务主机:Java服务端口`

## **6\. 项目结构**

```
matcher-server/
├── vcpkg.json              # 依赖清单
├── CMakeLists.txt          # 构建脚本
├── include/                # 头文件
│   ├── core/               # 撮合引擎核心
│   ├── ipc/                # IPC 通信
│   ├── model/              # 数据模型
│   ├── persistence/        # 持久化层
│   └── util/               # 工具类
├── src/                    # 源代码实现
├── tests/                  # 单元测试
└── logs/                   # 运行时日志目录
```

## **7\. 生产环境部署**

### **7.1 部署前准备**

- [ ] 确认服务器已安装编译工具链 (GCC 10+)
- [ ] 确认 CMake 3.20+ 和 vcpkg 已配置
- [ ] 创建日志目录: `mkdir -p /opt/matcher-server/logs`
- [ ] 设置适当的文件权限

### **7.2 编译发布版本**

```bash
cd matcher-server
cmake -B build -S . \
  -DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

### **7.3 启动服务**

**⚠️ 生产环境必须使用 PRODUCTION 模式**

```bash
# 直接启动
./matcher_server --mode PRODUCTION --ipc http:9001:localhost:8081

# 后台运行
nohup ./matcher_server --mode PRODUCTION --ipc http:9001:localhost:8081 > startup.log 2>&1 &
```

### **7.4 systemd 服务配置 (推荐)**

创建 `/etc/systemd/system/matcher-server.service`:

```ini
[Unit]
Description=Trading Matcher Server
After=network.target

[Service]
Type=simple
User=trading
WorkingDirectory=/opt/matcher-server
ExecStart=/opt/matcher-server/bin/matcher_server --mode PRODUCTION --ipc http:9001:localhost:8081
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启用服务:

```bash
sudo systemctl daemon-reload
sudo systemctl enable matcher-server
sudo systemctl start matcher-server
sudo systemctl status matcher-server
```

## **8\. 运维操作**

### **8.1 日志文件**

程序在 `logs/` 目录生成三个日志文件:

| 文件 | 内容 | 用途 |
|------|------|------|
| `orders.log` | 订单生命周期 | 追踪订单提交、撮合、成交 |
| `db.log` | 数据库操作 | 持久化操作、SQL执行状态 |
| `system.log` | 系统运行状态 | 启动/停止、错误、性能信息 |

**查看日志:**

```bash
# 实时查看系统日志
tail -f logs/system.log

# 查找错误
grep "ERROR\|FAILURE" logs/*.log

# 查询特定订单
grep "ORDER123456" logs/orders.log
```

### **8.2 服务管理**

```bash
# 启动服务
sudo systemctl start matcher-server

# 停止服务
sudo systemctl stop matcher-server

# 重启服务
sudo systemctl restart matcher-server

# 查看状态
sudo systemctl status matcher-server
```

### **8.3 健康检查**

```bash
# 检查进程
ps aux | grep matcher_server

# 检查端口监听
netstat -tlnp | grep 9001

# 查看最近日志
tail -n 50 logs/system.log
```

### **8.4 临时文件说明**

**query 文件**: 
- MySQL C API 库自动生成的临时文件 (内容: "MySQL80")
- 可以安全删除，不影响程序运行
- 已添加到 `.gitignore`

```bash
# 清理临时文件
rm -f query
```

## **9\. 常见问题排查**

### **问题: 服务无法启动**

```bash
# 1. 查看启动日志
tail -n 50 logs/system.log

# 2. 检查端口占用
netstat -tlnp | grep 9001

# 3. 检查配置参数
ps aux | grep matcher_server
```

### **问题: 日志文件过大**

```bash
# 配置日志轮转 (logrotate)
cat > /etc/logrotate.d/matcher-server << EOF
/opt/matcher-server/logs/*.log {
    daily
    rotate 30
    compress
    missingok
    notifempty
}
EOF

# 手动清理旧日志
find logs/ -name "*.log.*" -mtime +30 -delete
```

### **问题: 内存持续增长**

```bash
# 监控内存使用
watch -n 1 'ps aux | grep matcher_server'

# 检查日志中的异常
grep "ERROR\|FAILURE" logs/system.log
```

## **10\. 安全注意事项**

1. **生产环境禁用 TEST 模式** - TEST 模式直接连接数据库，绕过安全控制
2. **限制端口访问** - 配置防火墙限制 IPC 端口访问
3. **使用专用用户** - 不要使用 root 运行服务
4. **定期更新依赖** - 及时更新 vcpkg 依赖库修复安全漏洞

## **11\. 开发规范**

* **C++ 标准**: C++17
* **编码格式**: 遵循项目根目录 `.clang-format`
* **禁止事项**:
  * 禁止在热路径中使用 new/malloc (使用内存池)
  * 禁止在撮合线程中打印同步日志 (使用异步日志)
  * 禁止使用 C++ iostream (cout/cin) 进行 I/O

## **12\. 性能指标参考**

- **订单处理延迟**: < 1ms (P99)
- **吞吐量**: > 10,000 orders/sec
- **内存占用**: < 500MB (正常运行)
- **CPU 使用率**: < 30% (正常负载)

*注: 实际性能取决于硬件配置和订单复杂度*
