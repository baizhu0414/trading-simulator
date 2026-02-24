/**
 * @file main.cpp
 * @brief 撮合引擎主程序入口
 * 
 * 负责：
 * 1. 解析命令行参数和配置文件
 * 2. 根据运行模式（TEST/PRODUCTION）选择持久化策略
 * 3. 初始化IPC通信服务器
 * 4. 创建并注入核心引擎依赖
 * 5. 启动系统
 */

#include <iostream>
#include <memory>
#include <string>
#include <cstring>
#include <sstream>
#include <thread>
#include <chrono>
#include <filesystem>
#include <locale>
#ifdef _WIN32
#include <windows.h>
#endif
#include "ipc/IPCServer.h"
#include "persistence/IPersistence.h"
#include "persistence/MySQLPersistence.h"
#include "persistence/ProxyPersistence.h"
#include "util/Logger.h"
#include "core/IMatchingEngine.h"

// TODO: 包含核心引擎实现头文件（由其他开发者实现）
// #include "core/MatchingEngine.h"

using namespace matcher;

/**
 * @brief 程序配置结构体
 */
struct Config {
    std::string mode = "TEST";         // 运行模式: TEST或PRODUCTION
    std::string mysqlUrl = "mysqlx://root:password@localhost:3306/trading"; // MySQL连接字符串
    std::string ipcConfig = "stdio";   // IPC配置
    bool verbose = false;              // 详细日志输出
};

/**
 * @brief 解析命令行参数
 * 
 * @param argc 参数数量
 * @param argv 参数数组
 * @return Config 配置信息
 */
Config parseArguments(int argc, char* argv[]) {
    Config config;
    
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--mode") == 0 && i + 1 < argc) {
            config.mode = argv[++i];
        } else if (strcmp(argv[i], "--mysql") == 0 && i + 1 < argc) {
            config.mysqlUrl = argv[++i];
        } else if (strcmp(argv[i], "--ipc") == 0 && i + 1 < argc) {
            config.ipcConfig = argv[++i];
        } else if (strcmp(argv[i], "--verbose") == 0 || strcmp(argv[i], "-v") == 0) {
            config.verbose = true;
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            std::cout << "Usage: " << argv[0] << " [options]\n";
            std::cout << "Options:\n";
            std::cout << "  --mode <TEST|PRODUCTION>   Run mode (default: TEST)\n";
            std::cout << "  --mysql <url>              MySQL connection string\n";
            std::cout << "  --ipc <config>             IPC config (default: stdio)\n";
            std::cout << "  --verbose, -v              Verbose logging\n";
            std::cout << "  --help, -h                 Show help\n";
            std::exit(0);
        }
    }
    
    return config;
}

/**
 * @brief 显示启动横幅
 */
void showBanner(const Config& config) {
    std::cout << "========================================\n";
    std::cout << "   C++ Matcher Server v0.1.0  \n";
    std::cout << "========================================\n";
    std::cout << "Run mode: " << config.mode << "\n";
    std::cout << "Persistence strategy: " 
              << (config.mode == "TEST" ? "MySQL direct" : "Remote proxy (Java)") << "\n";
    std::cout << "IPC config: " << config.ipcConfig << "\n";
    std::cout << "========================================\n\n";
}

/**
 * @brief 初始化日志系统
 */
bool initLogger(const Config& config) {
    // 构建日志配置为单行 JSON 字符串（避免跨编码原始字符串字面量问题）
    std::string level = config.verbose ? "DEBUG" : "INFO";
    std::ostringstream lcfg;
    lcfg << "{\"level\":\"" << level << "\","
         << "\"async_mode\":true,"
         << "\"flush_interval\":1,"
         << "\"sinks\":["
         << "{\"type\":\"file\",\"filename\":\"logs/orders.log\",\"rotation_size\":10485760},"
         << "{\"type\":\"file\",\"filename\":\"logs/db.log\",\"rotation_size\":10485760},"
         << "{\"type\":\"file\",\"filename\":\"logs/system.log\",\"rotation_size\":10485760}"
         << "]}"
    ;
    std::string logConfig = lcfg.str();
    
    // Ensure logs directory exists
    try {
        std::filesystem::create_directories("logs");
    } catch (...) {
        std::cerr << "[Init] Failed to create logs directory\n";
        return false;
    }

    std::cout << "[Init] Logger config: " << logConfig.substr(0, 100) << "...\n";
    bool ok = util::Logger::init(logConfig);
    if (!ok) {
        std::cerr << "[Init] Logger initialization failed\n";
        return false;
    }
    std::cout << "[Init] Logger ready\n";
    return true;
}

/**
 * @brief 创建持久化实例
 * 
 * @param config 配置信息
 * @param ipcServer IPC服务器实例（用于PRODUCTION模式）
 * @return std::unique_ptr<persistence::IPersistence> 持久化实例
 */
std::unique_ptr<persistence::IPersistence> createPersistence(
    const Config& config,
    ipc::IPCServer* ipcServer) {
    
    std::cout << "[Init] Creating persistence instance (mode: " << config.mode << ")\n";
    
    if (config.mode == "TEST") {
        // 测试模式：使用MySQL直连
        auto persistence = std::make_unique<persistence::MySQLPersistence>(config.mysqlUrl);
        std::cout << "[Init] Using MySQL direct persistence\n";
        return persistence;
    } else if (config.mode == "PRODUCTION") {
        // 生产模式：使用远程代理
        if (!ipcServer) {
            throw std::runtime_error("PRODUCTION模式需要有效的IPC服务器实例");
        }
        auto persistence = std::make_unique<persistence::ProxyPersistence>(ipcServer);
        std::cout << "[Init] Using remote proxy persistence\n";
        return persistence;
    } else {
        throw std::runtime_error("未知的运行模式: " + config.mode);
    }
}

int main(int argc, char* argv[]) {
    try {
    // Ensure Windows console uses UTF-8 so UTF-8 encoded output appears correctly
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
    std::locale::global(std::locale(""));
    std::cout.imbue(std::locale());
    std::cerr.imbue(std::locale());
        // 1. 解析配置
        Config config = parseArguments(argc, argv);
        
        // 2. 显示启动信息
        showBanner(config);
        
        // 3. 初始化日志系统
        if (!initLogger(config)) {
            std::cerr << "[错误] 日志系统初始化失败\n";
            return 1;
        }
        
        // 4. 创建IPC服务器
        std::cout << "[Init] Creating IPC server...\n";
        auto ipcServer = ipc::createIPCServer(config.ipcConfig);
        if (!ipcServer) {
            std::cerr << "[Error] Failed to create IPC server\n";
            return 1;
        }
        
        // 5. 创建持久化实例
        auto persistence = createPersistence(config, ipcServer.get());

        // 6. 创建核心引擎
        std::cout << "[Init] Creating core matching engine...\n";
        auto engine = core::createMatchingEngine(std::move(persistence), ipcServer.get());
        if (!engine) {
            std::cerr << "[Error] Failed to create matching engine\n";
            return 1;
        }
        std::cout << "[Init] Matching engine ready\n";
        
        // 7. 设置IPC回调（TODO: 等引擎实现后取消注释）
        std::cout << "[Init] Setting IPC callbacks...\n";
        
        // 单订单回调
        // capture raw pointer to engine (engine owns the instance in this scope)
        core::IMatchingEngine* enginePtr = engine.get();
        ipcServer->setOrderCallback([enginePtr](model::Order order) {
            if (enginePtr) enginePtr->submitOrder(order);
            std::cout << "[IPC] Received new order: " << order.clOrderId << "\n";
        });
        
        // 批量撮合回调
        ipcServer->setMatchCallback([enginePtr](
            const std::string& market,
            const std::string& securityId,
            const std::vector<model::Order>& buyOrders,
            const std::vector<model::Order>& sellOrders) {
            std::cout << "[IPC] Received batch match request: " << securityId << "\n";
            if (enginePtr) return enginePtr->matchBatch(market, securityId, buyOrders, sellOrders);
            return std::vector<model::Trade>();
        });
        
        // 8. 启动IPC服务器
        std::cout << "[Start] Starting IPC server...\n";
        if (!ipcServer->start()) {
            std::cerr << "[Error] IPC server failed to start\n";
            return 1;
        }
        
        std::cout << "\n[Status] Matcher engine started and running\n";
        std::cout << "Waiting for orders (Ctrl+C to exit)...\n\n";
        
        // 9. 主循环
        bool running = true;
        while (running) {
            // 简单的主循环，实际实现中可能处理其他事件
            std::this_thread::sleep_for(std::chrono::seconds(1));
            
            // 可以添加心跳检查、性能监控等
        }
        
        // 10. 停止系统
        std::cout << "\n[Stop] Shutting down system...\n";
        ipcServer->stop();
        std::cout << "[Stop] System shut down\n";
        
        return 0;
        
    } catch (const std::exception& e) {
        std::cerr << "[Exception] " << e.what() << "\n";
        return 1;
    } catch (...) {
        std::cerr << "[Exception] Unknown error\n";
        return 1;
    }
}