#include <iostream>
#include <filesystem>
#include <thread>
#include <chrono>
#include "util/Logger.h"

int main() {
    using namespace matcher::util;
    // ensure logs dir exists
    std::filesystem::create_directories("logs");

    if (!Logger::init("{}")) {
        std::cerr << "Logger init failed" << std::endl;
        return 1;
    }

    Logger::logOrder("O-1001", "Created order for testing");
    Logger::logDB("INSERT", "SUCCESS", "Inserted test trade");
    Logger::logSys("logger_test", "System message example");
    Logger::logError("logger_test", "An example error");
    Logger::logTrace("test_op", 42);

    // Give worker thread time to flush
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    Logger::shutdown();

    std::cout << "Logger test finished. Check logs/ for output." << std::endl;
    return 0;
}
