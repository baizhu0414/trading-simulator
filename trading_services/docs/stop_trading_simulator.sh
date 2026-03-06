#!/bin/bash
set -euo pipefail

# 配置项
SPRING_BOOT_JAR="trading-simulator-0.0.1-SNAPSHOT.jar"
# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始停止 trading-simulator ===${NC}"

# 检查是否运行
SPRING_BOOT_PID=$(pgrep -u group10 -f "${SPRING_BOOT_JAR}" || true)
if [ -z "${SPRING_BOOT_PID}" ]; then
  echo -e "${YELLOW}trading-simulator未运行，跳过停止${NC}"
  exit 0
fi

# 优雅停止（SIGTERM）
echo -n "优雅停止应用（PID：${SPRING_BOOT_PID}），等待10秒..."
kill -15 "${SPRING_BOOT_PID}"

# 等待进程退出
for i in {1..10}; do
  if ! ps -p "${SPRING_BOOT_PID}" > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

# 兜底强制停止
if ps -p "${SPRING_BOOT_PID}" > /dev/null 2>&1; then
  echo -e "\n${YELLOW}优雅停止失败，强制终止（PID：${SPRING_BOOT_PID}）${NC}"
  kill -9 "${SPRING_BOOT_PID}"
  sleep 1
fi

# 验证停止结果
if ! pgrep -u group10 -f "${SPRING_BOOT_JAR}" > /dev/null 2>&1; then
  echo -e "${GREEN}trading-simulator停止成功${NC}"
else
  echo -e "${RED}停止失败，请手动执行：kill -9 $(pgrep -u group10 -f ${SPRING_BOOT_JAR})${NC}"
  exit 1
fi