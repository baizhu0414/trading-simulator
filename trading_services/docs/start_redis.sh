#!/bin/bash
set -euo pipefail

# 配置项（和总脚本保持一致）
REDIS_SERVER="/home/group10/software/redis-7.0.14/redis/bin/redis-server"
REDIS_CLI="/home/group10/software/redis-7.0.14/redis/bin/redis-cli"
REDIS_DIR="/home/group10/software/redis-7.0.14/redis"
LOG_BASE_DIR="/home/group10/software/logs"
mkdir -p "${LOG_BASE_DIR}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始启动 Redis ===${NC}"

# 检查Redis可执行文件
if ! "${REDIS_SERVER}" --version > /dev/null 2>&1; then
  echo -e "${RED}Redis可执行文件不存在：${REDIS_SERVER}${NC}"
  exit 1
fi

# 检查是否已运行
if "${REDIS_CLI}" ping > /dev/null 2>&1; then
  echo -e "${YELLOW}Redis已运行，跳过启动${NC}"
  exit 0
fi

# 启动Redis（后台运行，指定数据目录）
mkdir -p "${REDIS_DIR}/data"
"${REDIS_SERVER}" --daemonize yes --dir "${REDIS_DIR}/data" > "${LOG_BASE_DIR}/redis.log" 2>&1
sleep 1

# 验证启动结果
if "${REDIS_CLI}" ping > /dev/null 2>&1; then
  echo -e "${GREEN}Redis启动成功${NC}"
else
  echo -e "${RED}Redis启动失败，查看日志：${LOG_BASE_DIR}/redis.log${NC}"
  exit 1
fi