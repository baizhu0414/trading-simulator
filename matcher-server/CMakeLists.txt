cmake_minimum_required(VERSION 3.20)

# 自动检测 vcpkg 工具链
if(NOT DEFINED CMAKE_TOOLCHAIN_FILE AND DEFINED ENV{VCPKG_ROOT})
    set(CMAKE_TOOLCHAIN_FILE "$ENV{VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake"
        CACHE STRING "Vcpkg toolchain file")
endif()

project(MatcherServer VERSION 0.1.0 LANGUAGES CXX)

# 设置 C++ 标准 (C++17 是现代金融交易系统的基准)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 自动处理 vcpkg 依赖
# 注意：使用 Manifest 模式时，find_package 会自动找到 vcpkg 安装的库

# 调试信息
message(STATUS "CMAKE_TOOLCHAIN_FILE: ${CMAKE_TOOLCHAIN_FILE}")
message(STATUS "VCPKG_INSTALLED_DIR: ${VCPKG_INSTALLED_DIR}")
message(STATUS "CMAKE_PREFIX_PATH: ${CMAKE_PREFIX_PATH}")

# 方法1：首先尝试不使用CONFIG模式
# 这会先让CMake自动查找，如果失败再尝试其他方法
# 注意：vcpkg manifest模式可能需要特殊处理
# 首先尝试不使用CONFIG模式
find_package(nlohmann_json REQUIRED)
find_package(spdlog CONFIG REQUIRED)
find_package(GTest CONFIG REQUIRED)
# 使用libmysql（MySQL C客户端库）
# 根据vcpkg文档，应该使用unofficial::libmysql::libmysql
find_package(unofficial-libmysql REQUIRED)

# 使用 cpp-httplib (Header-only library, typically available via CONFIG or module mode)
find_package(httplib CONFIG REQUIRED)

# 包含头文件目录
include_directories(include)

# 1. 核心库 (Core Library) - 将逻辑与 main 分离，方便测试
add_library(matcher_core
    # src/core/OrderBook.cpp (文件不存在，暂时注释)
    # src/core/MatchingEngine.cpp (后续添加)
    src/ipc/HttpIPCServer.cpp
    src/persistence/MySQLPersistence.cpp
    src/persistence/ProxyPersistence.cpp
)
# 指定头文件搜索路径
target_include_directories(matcher_core PUBLIC include)
# 链接依赖 #private 表示这些库仅在 matcher_core 内部使用，不会暴露给依赖 matcher_core 的目标,可能导致问题
target_link_libraries(matcher_core PRIVATE 
    nlohmann_json::nlohmann_json 
    spdlog::spdlog 
    unofficial::libmysql::libmysql # vcpkg 中 libmysql 暴露的 target
    httplib::httplib               # HTTP library target
)
# 2. 可执行文件 (Executable)
add_executable(matcher_server src/main.cpp)
target_link_libraries(matcher_server PRIVATE matcher_core)

# 3. 测试 (Tests) - 暂时禁用，因为测试文件不存在
# enable_testing()
# add_executable(unit_tests tests/test_main.cpp)
# target_link_libraries(unit_tests PRIVATE matcher_core GTest::gtest_main)
# add_test(NAME CoreTests COMMAND unit_tests)
