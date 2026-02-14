cmake_minimum_required(VERSION 3.20)

project(MatcherServer VERSION 0.1.0 LANGUAGES CXX)

# 设置 C++ 标准 (C++17 是现代金融交易系统的基准)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 自动处理 vcpkg 依赖
# 注意：使用 Manifest 模式时，find_package 会自动找到 vcpkg 安装的库

find_package(nlohmann_json CONFIG REQUIRED)
find_package(spdlog CONFIG REQUIRED)
find_package(GTest CONFIG REQUIRED)
# find_package(mysql-connector-cpp CONFIG REQUIRED) # 根据需要开启

# 包含头文件目录
include_directories(include)

# 1. 核心库 (Core Library) - 将逻辑与 main 分离，方便测试
add_library(matcher_core
    src/core/OrderBook.cpp
    # src/core/MatchingEngine.cpp (后续添加)
)
# 指定头文件搜索路径
target_include_directories(matcher_core PUBLIC include)
# 链接依赖
target_link_libraries(matcher_core PRIVATE nlohmann_json::nlohmann_json spdlog::spdlog)

# 2. 可执行文件 (Executable)
add_executable(matcher_server src/main.cpp)
target_link_libraries(matcher_server PRIVATE matcher_core)

# 3. 测试 (Tests)
enable_testing()
add_executable(unit_tests tests/test_main.cpp)
target_link_libraries(unit_tests PRIVATE matcher_core GTest::gtest_main)
add_test(NAME CoreTests COMMAND unit_tests)