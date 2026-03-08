#!/bin/bash
set -euo pipefail

# 配置项
MYSQL_DIR="/home/group10/software/mysql"
MYSQL_DATA_DIR="${MYSQL_DIR}/data"
# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始停止 MySQL ===${NC}"

# 修复1：精准匹配mysqld主进程（排除日志/其他无关匹配）
# 只匹配 mysqld 二进制进程，不匹配 mysqld_safe/日志等
MYSQL_PIDS=$(pgrep -u group10 -x mysqld || true)
MYSQL_SAFE_PIDS=$(pgrep -u group10 -x mysqld_safe || true)

# 合并所有关联PID
ALL_MYSQL_PIDS="${MYSQL_PIDS} ${MYSQL_SAFE_PIDS}"
ALL_MYSQL_PIDS=$(echo "${ALL_MYSQL_PIDS}" | xargs) # 去空

if [ -z "${ALL_MYSQL_PIDS}" ]; then
  echo -e "${YELLOW}MySQL未运行，跳过停止${NC}"
  exit 0
fi
echo -e "${YELLOW}检测到MySQL关联进程PID：${ALL_MYSQL_PIDS}${NC}"

# 优先优雅停止（支持密码登录）
MYSQLADMIN="${MYSQL_DIR}/bin/mysqladmin"
if [ -f "${MYSQLADMIN}" ]; then
  # 修复2：支持手动输入密码，避免免密配置问题
  echo -n "${YELLOW}请输入MySQL root密码（无密码直接回车）：${NC}"
  read -s MYSQL_PASSWORD
  echo ""
  if [ -n "${MYSQL_PASSWORD}" ]; then
    "${MYSQLADMIN}" -uroot -p"${MYSQL_PASSWORD}" shutdown > /dev/null 2>&1
  else
    "${MYSQLADMIN}" -uroot shutdown > /dev/null 2>&1
  fi

  if [ $? -eq 0 ]; then
    echo -n "${GREEN}MySQL优雅停止中，等待5秒...${NC}"
    sleep 5
  else
    echo -e "${YELLOW}mysqladmin优雅停止失败，执行强制停止${NC}"
  fi
fi

# 修复3：逐个强制杀死PID（比pkill更精准）
for PID in ${ALL_MYSQL_PIDS}; do
  if ps -p "${PID}" > /dev/null 2>&1; then
    echo -e "${YELLOW}强制终止PID ${PID}...${NC}"
    kill -9 "${PID}" > /dev/null 2>&1
  fi
done
sleep 3 # 延长等待时间，确保进程退出

# 修复4：再次检查所有MySQL进程（精准匹配）
MYSQL_PIDS_AFTER=$(pgrep -u group10 -x mysqld || true)
MYSQL_SAFE_PIDS_AFTER=$(pgrep -u group10 -x mysqld_safe || true)
ALL_MYSQL_PIDS_AFTER="${MYSQL_PIDS_AFTER} ${MYSQL_SAFE_PIDS_AFTER}"
ALL_MYSQL_PIDS_AFTER=$(echo "${ALL_MYSQL_PIDS_AFTER}" | xargs)

# 验证停止结果 + 清理残留文件
if [ -z "${ALL_MYSQL_PIDS_AFTER}" ]; then
  # 清理PID文件和套接字文件
  rm -f "${MYSQL_DATA_DIR}/VM-0-3-rockylinux.pid" /tmp/mysql.sock /tmp/mysql.sock.lock
  echo -e "${GREEN}MySQL停止成功${NC}"
else
  echo -e "${RED}MySQL停止失败，残留进程PID：${ALL_MYSQL_PIDS_AFTER}${NC}"
  echo -e "${RED}请手动执行：kill -9 ${ALL_MYSQL_PIDS_AFTER}${NC}"
  exit 1
fi