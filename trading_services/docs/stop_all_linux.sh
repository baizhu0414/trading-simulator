#!/bin/bash
set -euo pipefail  # 新增：严格模式，避免空变量/命令失败导致逻辑异常

# 统一定义路径（和启动脚本保持一致，减少维护成本）
SPRING_BOOT_JAR_NAME="trading-simulator-0.0.1-SNAPSHOT.jar"
GRAFANA_DIR="/home/group10/software/grafana-10.2.0"
MYSQL_DIR="/home/group10/software/mysql-8.4.0-linux-glibc2.28-x86_64"
MYSQL_EXPORTER_NAME="mysqld_exporter"
PROMETHEUS_DIR="/home/group10/software/prometheus-3.9.1.linux-amd64"
REDIS_CLI="/home/group10/software/redis-7.0.14/redis/bin/redis-cli"
# 新增：Redis密码（根据实际配置修改，无密码则留空）
REDIS_PASSWORD=""
# 新增：MySQL数据目录（和启动脚本一致，避免硬编码）
MYSQL_DATA_DIR="${MYSQL_DIR}/data"

# 定义颜色输出（便于区分日志级别）
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== 开始停止所有服务 ===${NC}"

# 1. 停止 Spring Boot 应用（修复：空PID跳过kill，避免报错）
echo -n "--- 停止 Spring Boot 应用："
SPRING_BOOT_PID=$(pgrep -u group10 -f "${SPRING_BOOT_JAR_NAME}" || true)
if [ -n "${SPRING_BOOT_PID}" ]; then
  # 发送SIGTERM信号（15）优雅终止，而非强制kill（9）
  kill -15 "${SPRING_BOOT_PID}"
  # 等待进程退出（最多10秒）
  for i in {1..10}; do
    if ! ps -p "${SPRING_BOOT_PID}" > /dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  # 若仍未退出，提示并强制终止（兜底）
  if ps -p "${SPRING_BOOT_PID}" > /dev/null 2>&1; then
    echo -e "${YELLOW}优雅停止失败，强制终止${NC}"
    kill -9 "${SPRING_BOOT_PID}"
  else
    echo -e "${GREEN}成功${NC}"
  fi
else
  echo -e "${YELLOW}未运行${NC}"
fi

# 2. 停止 Grafana（修复：强制终止后二次验证，避免误报成功）
echo -n "--- 停止 Grafana："
# 最宽松匹配：只要进程里有 grafana 关键词，且是 group10 的（用单引号避免转义）
GRAFANA_PID=$(pgrep -u group10 -f 'grafana' || true)
if [ -n "${GRAFANA_PID}" ]; then
  # Grafana 二进制版推荐用 SIGINT/SIGTERM 优雅停止
  kill -15 "${GRAFANA_PID}"
  echo -n "（优雅停止中，等待2秒）"
  sleep 2
  # 二次验证：先看原 PID，再看是否还有 grafana 进程
  if ! ps -p "${GRAFANA_PID}" > /dev/null 2>&1 && ! pgrep -u group10 -f 'grafana' > /dev/null 2>&1; then
    echo -e "${GREEN}成功${NC}"
  else
    echo -e "${YELLOW}优雅停止失败，强制终止${NC}"
    pkill -9 -u group10 -f 'grafana'
    sleep 1
    # 新增：强制终止后二次验证
    if ! pgrep -u group10 -f 'grafana' > /dev/null 2>&1; then
      echo -e "${GREEN}成功${NC}"
    else
      echo -e "${RED}强制终止仍失败，请手动处理${NC}"
    fi
  fi
else
  echo -e "${YELLOW}未运行${NC}"
fi

# 3. 停止 MySQL Exporter（修复：空PID跳过kill，避免报错）
echo -n "--- 停止 MySQL Exporter："
MYSQL_EXPORTER_PID=$(pgrep -u group10 -f "${MYSQL_EXPORTER_NAME}" || true)
if [ -n "${MYSQL_EXPORTER_PID}" ]; then
  kill -15 "${MYSQL_EXPORTER_PID}"
  sleep 1
  if ! ps -p "${MYSQL_EXPORTER_PID}" > /dev/null 2>&1; then
    echo -e "${GREEN}成功${NC}"
  else
    echo -e "${YELLOW}优雅停止失败，强制终止${NC}"
    kill -9 "${MYSQL_EXPORTER_PID}"
  fi
else
  echo -e "${YELLOW}未运行${NC}"
fi

# 4. 停止 Prometheus（修复：空PID跳过kill，避免报错）
echo -n "--- 停止 Prometheus："
# 放宽匹配：只要进程里有 prometheus 关键词，且是 group10 的
PROMETHEUS_PID=$(pgrep -u group10 -f "prometheus" || true)
if [ -n "${PROMETHEUS_PID}" ]; then
  # Prometheus 接收 SIGINT/SIGTERM 会优雅退出，保存数据
  kill -2 "${PROMETHEUS_PID}" # SIGINT 等同于 Ctrl+C，更贴合Prometheus停止习惯
  sleep 2
  if ! ps -p "${PROMETHEUS_PID}" > /dev/null 2>&1; then
    echo -e "${GREEN}成功${NC}"
  else
    echo -e "${YELLOW}优雅停止失败，强制终止${NC}"
    kill -9 "${PROMETHEUS_PID}"
  fi
else
  echo -e "${YELLOW}未运行${NC}"
fi

# 5. 停止 MySQL（修复：1. 适配MySQL数据目录 2. 增加mysqladmin失败提示 3. 空PID跳过逻辑）
echo -n "--- 停止 MySQL："
MYSQLADMIN="${MYSQL_DIR}/bin/mysqladmin"
# 1. 优先用进程检查是否运行（最可靠，完全避免密码问题）
MYSQL_PID=$(pgrep -u group10 -f "mysqld" || true)
if [ -n "${MYSQL_PID}" ]; then
  # 2. 先用 mysqladmin 优雅停止（增加失败提示）
  if "${MYSQLADMIN}" -uroot shutdown > /dev/null 2>&1; then
    echo -n "（优雅停止中，等待3秒）"
    sleep 3
  else
    echo -n "${YELLOW}（mysqladmin优雅停止失败，可能无免密配置，尝试强制停止）${NC}"
  fi

  # 3. 如果优雅停止失败，兜底杀进程
  if pgrep -u group10 -f "mysqld" > /dev/null 2>&1; then
    echo -n "（优雅停止失败，强制杀进程）"
    pkill -u group10 -f mysqld
    pkill -u group10 -f mysqld_safe
    sleep 2
  fi

  # 4. 最终验证停止（进程检查）
  if ! pgrep -u group10 -f "mysqld" > /dev/null 2>&1; then
    # 修复：使用动态数据目录，清理残留文件
    rm -f "${MYSQL_DATA_DIR}/VM-0-3-rockylinux.pid" /tmp/mysql.sock /tmp/mysql.sock.lock
    echo -e "${GREEN}成功${NC}"
  else
    echo -e "${RED}失败${NC}"
  fi
else
  echo -e "${YELLOW}未运行${NC}"
fi

# 6. 停止 Redis（修复：支持密码认证，避免误判未运行）
echo -n "--- 停止 Redis："
# 构建Redis CLI命令（兼容有密码/无密码）
REDIS_CLI_CMD="${REDIS_CLI}"
if [ -n "${REDIS_PASSWORD}" ]; then
  REDIS_CLI_CMD="${REDIS_CLI_CMD} -a ${REDIS_PASSWORD}"
fi

# 先检查Redis是否运行（兼容密码）
if ${REDIS_CLI_CMD} ping > /dev/null 2>&1; then
  ${REDIS_CLI_CMD} shutdown
  sleep 1
  # 验证停止结果（兼容密码）
  if ! ${REDIS_CLI_CMD} ping > /dev/null 2>&1; then
    echo -e "${GREEN}成功${NC}"
  else
    echo -e "${RED}失败${NC}"
  fi
else
  echo -e "${YELLOW}未运行${NC}"
fi

echo -e "${GREEN}=== 所有服务停止流程执行完毕！===${NC}"