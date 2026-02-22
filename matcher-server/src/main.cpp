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
            std::cout << "  --mode <TEST|PRODUCTION>   运行模式 (默认: TEST)\n";
            std::cout << "  --mysql <url>              MySQL连接字符串\n";
            std::cout << "  --ipc <config>             IPC配置 (默认: stdio)\n";
            std::cout << "  --verbose, -v              详细日志输出\n";
            std::cout << "  --help, -h                 显示帮助信息\n";
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
    std::cout << "   C++ 撮合引擎 (Matcher Server) v0.1.0  \n";
    std::cout << "========================================\n";
    std::cout << "运行模式: " << config.mode << "\n";
    std::cout << "持久化策略: " 
              << (config.mode == "TEST" ? "MySQL直连" : "远程代理(Java层)") << "\n";
    std::cout << "IPC配置: " << config.ipcConfig << "\n";
    std::cout << "========================================\n\n";
}

/**
 * @brief 初始化日志系统
 */
bool initLogger(const Config& config) {
    // 构建日志配置
    std::string logConfig = R"({
        "level": ")" + std::string(config.verbose ? "DEBUG" : "INFO") + R"(",
        "async_mode": true,
        "flush_interval": 1,
        "sinks": [
            {
                "type": "file",
                "filename": "logs/orders.log",
                "rotation_size": 10485760
            },
            {
                "type": "file",
                "filename": "logs/db.log",
                "rotation_size": 10485760
            },
            {
                "type": "file",
                "filename": "logs/system.log",
                "rotation_size": 10485760
            }
        ]
    })";
    
    // 注意：Logger模块是占位实现，实际功能由其他开发者完成
    std::cout << "[初始化] 日志系统配置: " << logConfig.substr(0, 100) << "...\n";
    
    // 实际调用
    // return util::Logger::init(logConfig);
    
    std::cout << "[初始化] 日志系统已准备\n";
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
    
    std::cout << "[初始化] 创建持久化实例 (模式: " << config.mode << ")\n";
    
    if (config.mode == "TEST") {
        // 测试模式：使用MySQL直连
        auto persistence = std::make_unique<persistence::MySQLPersistence>(config.mysqlUrl);
        std::cout << "[初始化] 使用MySQL直连持久化\n";
        return persistence;
    } else if (config.mode == "PRODUCTION") {
        // 生产模式：使用远程代理
        if (!ipcServer) {
            throw std::runtime_error("PRODUCTION模式需要有效的IPC服务器实例");
        }
        auto persistence = std::make_unique<persistence::ProxyPersistence>(ipcServer);
        std::cout << "[初始化] 使用远程代理持久化\n";
        return persistence;
    } else {
        throw std::runtime_error("未知的运行模式: " + config.mode);
    }
}

int main(int argc, char* argv[]) {
    try {
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
        std::cout << "[初始化] 创建IPC服务器...\n";
        auto ipcServer = ipc::createIPCServer(config.ipcConfig);
        if (!ipcServer) {
            std::cerr << "[错误] 无法创建IPC服务器\n";
            return 1;
        }
        
        // 5. 创建持久化实例
        auto persistence = createPersistence(config, ipcServer.get());
        
        // 6. 创建核心引擎（TODO: 由其他开发者实现）
        std::cout << "[初始化] 创建核心撮合引擎...\n";
        // auto engine = std::make_unique<core::MatchingEngine>(std::move(persistence));
        
        // 7. 设置IPC回调
        std::cout << "[初始化] 设置IPC回调...\n";
        ipcServer->setOrderCallback([](model::Order order) {
            // TODO: 将订单传递给核心引擎
            // engine->onOrderInput(std::move(order));
            std::cout << "[IPC] 接收到新订单: " << order.clOrderId << "\n";
        });
        
        // 8. 启动IPC服务器
        std::cout << "[启动] 启动IPC服务器...\n";
        if (!ipcServer->start()) {
            std::cerr << "[错误] IPC服务器启动失败\n";
            return 1;
        }
        
        std::cout << "\n[系统状态] 撮合引擎已启动并运行\n";
        std::cout << "等待订单输入 (按Ctrl+C退出)...\n\n";
        
        // 9. 主循环
        bool running = true;
        while (running) {
            // 简单的主循环，实际实现中可能处理其他事件
            std::this_thread::sleep_for(std::chrono::seconds(1));
            
            // 可以添加心跳检查、性能监控等
        }
        
        // 10. 停止系统
        std::cout << "\n[停止] 正在关闭系统...\n";
        ipcServer->stop();
        std::cout << "[停止] 系统已关闭\n";
        
        return 0;
        
    } catch (const std::exception& e) {
        std::cerr << "[异常] " << e.what() << "\n";
        return 1;
    } catch (...) {
        std::cerr << "[异常] 未知错误\n";
        return 1;
    }
}