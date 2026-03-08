#!/bin/bash
set -euo pipefail

# 配置项
MYSQL_DIR="/home/group10/software/mysql"  # 软链接路径，适配实际目录
MYSQL_CNF="${MYSQL_DIR}/my.cnf"
LOG_BASE_DIR="/home/group10/software/logs"
mkdir -p "${LOG_BASE_DIR}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始启动 MySQL ===${NC}"

# 检查关键文件
MYSQLD_SAFE="${MYSQL_DIR}/bin/mysqld_safe"
if [ ! -f "${MYSQLD_SAFE}" ]; then
  echo -e "${RED}mysqld_safe不存在：${MYSQLD_SAFE}${NC}"
  exit 1
fi

# 检查是否已运行
if pgrep -u group10 -f "mysqld" > /dev/null 2>&1; then
  echo -e "${YELLOW}MySQL已运行，跳过启动${NC}"
  exit 0
fi

# 启动MySQL（后台运行，指定配置文件）
"${MYSQLD_SAFE}" --defaults-file="${MYSQL_CNF}" > "${LOG_BASE_DIR}/mysql.log" 2>&1 &
echo -n "MySQL启动中，等待5秒..."
sleep 5

# 验证启动结果
if pgrep -u group10 -f "mysqld" > /dev/null 2>&1; then
  echo -e "\n${GREEN}MySQL启动成功${NC}"
else
  echo -e "\n${RED}MySQL启动失败，查看错误日志：${MYSQL_DIR}/data/VM-0-3-rockylinux.err${NC}"
  exit 1
fi