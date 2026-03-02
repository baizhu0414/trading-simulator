#include "util/Logger.h"
#include <fstream>
#include <queue>
#include <thread>
#include <condition_variable>
#include <atomic>
#include <mutex>
#include <sstream>
#include <chrono>
#include <deque>
#include <iomanip>
#include <filesystem>

namespace matcher {
namespace util {

struct Logger::Impl {
    std::ofstream orders;
    std::ofstream db;
    std::ofstream system;

        // 为每个日志文件保留最近日志行的内存缓冲区
        std::deque<std::string> orders_buf;
        std::deque<std::string> db_buf;
        std::deque<std::string> system_buf;
    
    std::string logsDir;  // 日志文件目录

    std::mutex qmutex;
    std::condition_variable cv;
    std::queue<std::string> queue;
    std::thread worker;
    std::atomic<bool> running{false};

    void run() {
        while (running) {
            std::unique_lock<std::mutex> lk(qmutex);
            cv.wait_for(lk, std::chrono::milliseconds(500), [&]{ return !queue.empty() || !running; });
            
            // 处理队列中的所有消息
            while (!queue.empty()) {
                auto msg = queue.front(); queue.pop();
                lk.unlock(); // 处理消息时解锁
                
                // 添加时间戳前缀
                auto now = std::chrono::system_clock::now();
                auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) % 1000;
                std::time_t t = std::chrono::system_clock::to_time_t(now);
                std::tm tm;
#ifdef _WIN32
                localtime_s(&tm, &t);
#else
                localtime_r(&t, &tm);
#endif
                std::ostringstream tsoss;
                tsoss << std::put_time(&tm, "%Y-%m-%d %H:%M:%S") << "." << std::setfill('0') << std::setw(3) << ms.count();
                std::string formatted = tsoss.str() + " " + msg;

                // 根据前缀进行简单路由，维护内存缓冲区并截断
                if (msg.rfind("[ORDER]", 0) == 0) {
                    if (orders.is_open()) {
                        orders << formatted << std::endl;
                        orders.flush(); // 确保立即写入
                    }
                    orders_buf.push_back(formatted);
                    if (orders_buf.size() > 2000) {
                        while (orders_buf.size() > 2000) orders_buf.pop_front();
                        // 使用截断后的内容重写文件
                        orders.close();
                        orders.open(logsDir + "/orders.log", std::ios::out | std::ios::trunc);
                        for (const auto &line : orders_buf) orders << line << std::endl;
                        orders.flush();
                    }
                } else if (msg.rfind("[DB]", 0) == 0) {
                    if (db.is_open()) {
                        db << formatted << std::endl;
                        db.flush();
                    }
                    db_buf.push_back(formatted);
                    if (db_buf.size() > 2000) {
                        while (db_buf.size() > 2000) db_buf.pop_front();
                        db.close();
                        db.open(logsDir + "/db.log", std::ios::out | std::ios::trunc);
                        for (const auto &line : db_buf) db << line << std::endl;
                        db.flush();
                    }
                } else {
                    if (system.is_open()) {
                        system << formatted << std::endl;
                        system.flush();
                    }
                    system_buf.push_back(formatted);
                    if (system_buf.size() > 2000) {
                        while (system_buf.size() > 2000) system_buf.pop_front();
                        system.close();
                        system.open(logsDir + "/system.log", std::ios::out | std::ios::trunc);
                        for (const auto &line : system_buf) system << line << std::endl;
                        system.flush();
                    }
                }
                
                lk.lock(); // 为下一次迭代重新加锁
            }
        }
        
        // 停止时的最终刷新
        if (orders.is_open()) orders.flush();
        if (db.is_open()) db.flush();
        if (system.is_open()) system.flush();
    }
};

std::unique_ptr<Logger::Impl> Logger::impl_ = nullptr;

bool Logger::init(const std::string& config) {
    try {
        impl_ = std::make_unique<Impl>();
        
        // 确定正确的日志目录（相对于 trading-simulator/logs）
        // 尝试多个可能的路径来找到正确的日志目录
        std::string logsDir;
        
        // 第一种尝试：../logs（从 matcher-server 目录运行时）
        if (std::filesystem::exists("../logs") || std::filesystem::exists("../logs/")) {
            logsDir = "../logs";
        }
        // 第二种尝试：logs（从 trading-simulator 目录运行时）
        else if (std::filesystem::exists("logs") || std::filesystem::exists("logs/")) {
            logsDir = "logs";
        }
        // 第三种尝试：../../../logs（回退）
        else {
            logsDir = "../../../logs";
        }
        
        std::filesystem::create_directories(logsDir);
        
        // 保存日志目录供后续使用
        impl_->logsDir = logsDir;
        
        // 以追加模式打开日志文件以保留现有日志
        impl_->orders.open(logsDir + "/orders.log", std::ios::out | std::ios::app);
        impl_->db.open(logsDir + "/db.log", std::ios::out | std::ios::app);
        impl_->system.open(logsDir + "/system.log", std::ios::out | std::ios::app);
        impl_->running = true;
        impl_->worker = std::thread([&]{ impl_->run(); });
        return true;
    } catch (...) {
        impl_.reset();
        return false;
    }
}

void Logger::logOrder(const std::string& orderId, const std::string& message) {
    if (!impl_) return;
    std::stringstream ss;
    ss << "[ORDER] [" << orderId << "] " << message;
    {
        std::lock_guard<std::mutex> lk(impl_->qmutex);
        impl_->queue.push(ss.str());
    }
    impl_->cv.notify_one();
}

void Logger::logDB(const std::string& operation, const std::string& status, const std::string& message) {
    if (!impl_) return;
    std::stringstream ss;
    ss << "[DB] [" << operation << "] [" << status << "] " << message;
    {
        std::lock_guard<std::mutex> lk(impl_->qmutex);
        impl_->queue.push(ss.str());
    }
    impl_->cv.notify_one();
}

void Logger::logSys(const std::string& function, const std::string& message) {
    if (!impl_) return;
    std::stringstream ss;
    ss << "[SYS] [" << function << "] " << message;
    {
        std::lock_guard<std::mutex> lk(impl_->qmutex);
        impl_->queue.push(ss.str());
    }
    impl_->cv.notify_one();
}

void Logger::logError(const std::string& component, const std::string& message) {
    if (!impl_) return;
    std::stringstream ss;
    ss << "[ERROR] [" << component << "] " << message;
    {
        std::lock_guard<std::mutex> lk(impl_->qmutex);
        impl_->queue.push(ss.str());
    }
    impl_->cv.notify_one();
}

void Logger::logTrace(const std::string& operation, long long durationMs) {
    if (!impl_) return;
    std::stringstream ss;
    ss << "[TRACE] [" << operation << "] duration=" << durationMs << "ms";
    {
        std::lock_guard<std::mutex> lk(impl_->qmutex);
        impl_->queue.push(ss.str());
    }
    impl_->cv.notify_one();
}

void Logger::shutdown() {
    if (!impl_) return;
    impl_->running = false;
    impl_->cv.notify_one();
    if (impl_->worker.joinable()) impl_->worker.join();
    impl_.reset();
}

} // namespace util
} // namespace matcher
