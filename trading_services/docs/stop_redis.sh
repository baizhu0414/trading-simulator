#!/bin/bash
set -euo pipefail

# 配置项
REDIS_CLI="/home/group10/software/redis-7.0.14/redis/bin/redis-cli"
REDIS_PASSWORD=""  # 有密码则填写，无密码留空
# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始停止 Redis ===${NC}"

# 构建带密码的Redis CLI命令
REDIS_CLI_CMD="${REDIS_CLI}"
if [ -n "${REDIS_PASSWORD}" ]; then
  REDIS_CLI_CMD="${REDIS_CLI_CMD} -a ${REDIS_PASSWORD}"
fi

# 检查是否运行
if ! ${REDIS_CLI_CMD} ping > /dev/null 2>&1; then
  echo -e "${YELLOW}Redis未运行，跳过停止${NC}"
  exit 0
fi

# 优雅停止Redis
${REDIS_CLI_CMD} shutdown > /dev/null 2>&1
sleep 1

# 验证停止结果
if ! ${REDIS_CLI_CMD} ping > /dev/null 2>&1; then
  echo -e "${GREEN}Redis停止成功${NC}"
else
  echo -e "${YELLOW}优雅停止失败，强制终止Redis进程${NC}"
  pkill -u group10 -f "redis-server"
  sleep 1
  echo -e "${GREEN}Redis强制停止成功${NC}"
fi