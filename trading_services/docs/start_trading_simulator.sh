#!/bin/bash
set -euo pipefail

# 配置项
SPRING_BOOT_DIR="/home/group10/trading_services"
SPRING_BOOT_JAR="trading-simulator-0.0.1-SNAPSHOT.jar"
LOG_BASE_DIR="/home/group10/software/logs"
mkdir -p "${LOG_BASE_DIR}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始启动 trading-simulator ===${NC}"

# 检查JAR包是否存在
JAR_PATH="${SPRING_BOOT_DIR}/${SPRING_BOOT_JAR}"
if [ ! -f "${JAR_PATH}" ]; then
  echo -e "${RED}JAR包不存在：${JAR_PATH}${NC}"
  exit 1
fi

# 检查是否已运行
if pgrep -u group10 -f "${SPRING_BOOT_JAR}" > /dev/null 2>&1; then
  echo -e "${YELLOW}trading-simulator已运行，跳过启动${NC}"
  exit 0
fi

# 启动Spring Boot应用（后台运行，日志重定向）
cd "${SPRING_BOOT_DIR}" || exit 1
nohup java -Xmx8G -Xms8G -XX:G1NewSizePercent=30 -XX:SurvivorRatio=6 -XX:MaxDirectMemorySize=2G -jar "${SPRING_BOOT_JAR}" > "${LOG_BASE_DIR}/spring_boot.log" 2>&1 &
echo -n "应用启动中，等待2秒..."
sleep 2

# 验证启动结果
if pgrep -u group10 -f "${SPRING_BOOT_JAR}" > /dev/null 2>&1; then
  echo -e "\n${GREEN}trading-simulator启动成功${NC}"
else
  echo -e "\n${RED}启动失败，查看日志：${LOG_BASE_DIR}/spring_boot.log${NC}"
  exit 1
fi