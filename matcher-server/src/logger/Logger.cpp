#include "util/Logger.h"
#include <fstream>
#include <queue>
#include <thread>
#include <condition_variable>
#include <atomic>
#include <mutex>
#include <sstream>
#include <chrono>

namespace matcher {
namespace util {

struct Logger::Impl {
    std::ofstream orders;
    std::ofstream db;
    std::ofstream system;

    std::mutex qmutex;
    std::condition_variable cv;
    std::queue<std::string> queue;
    std::thread worker;
    std::atomic<bool> running{false};

    void run() {
        while (running) {
            std::unique_lock<std::mutex> lk(qmutex);
            cv.wait_for(lk, std::chrono::milliseconds(500), [&]{ return !queue.empty() || !running; });
            while (!queue.empty()) {
                auto msg = queue.front(); queue.pop();
                // simple routing by prefix
                if (msg.rfind("[ORDER]", 0) == 0) {
                    if (orders.is_open()) orders << msg << std::endl;
                } else if (msg.rfind("[DB]", 0) == 0) {
                    if (db.is_open()) db << msg << std::endl;
                } else {
                    if (system.is_open()) system << msg << std::endl;
                }
            }
        }
        // flush remaining
        while (!queue.empty()) {
            auto msg = queue.front(); queue.pop();
            if (msg.rfind("[ORDER]", 0) == 0) {
                if (orders.is_open()) orders << msg << std::endl;
            } else if (msg.rfind("[DB]", 0) == 0) {
                if (db.is_open()) db << msg << std::endl;
            } else {
                if (system.is_open()) system << msg << std::endl;
            }
        }
        if (orders.is_open()) orders.flush();
        if (db.is_open()) db.flush();
        if (system.is_open()) system.flush();
    }
};

std::unique_ptr<Logger::Impl> Logger::impl_ = nullptr;

bool Logger::init(const std::string& config) {
    try {
        impl_ = std::make_unique<Impl>();
        // For simplicity use fixed file names (main already prepares logs/)
        impl_->orders.open("logs/orders.log", std::ios::app);
        impl_->db.open("logs/db.log", std::ios::app);
        impl_->system.open("logs/system.log", std::ios::app);
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
