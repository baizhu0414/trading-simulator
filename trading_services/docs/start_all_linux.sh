#!/bin/bash
set -euo pipefail  # 严格模式：出错立即退出，避免无效执行

# 统一定义应用路径（和停止脚本保持一致，便于维护）
REDIS_SERVER="/home/group10/software/redis-7.0.14/redis/bin/redis-server"
REDIS_DIR="/home/group10/software/redis-7.0.14/redis"
MYSQL_DIR="/home/group10/software/mysql"
MYSQL_CNF="${MYSQL_DIR}/my.cnf"
PROMETHEUS_DIR="/home/group10/software/prometheus"
MYSQL_EXPORTER_DIR="/home/group10/software/mysqld_exporter"
GRAFANA_DIR="/home/group10/software/grafana"
SPRING_BOOT_DIR="/home/group10/trading_services"
SPRING_BOOT_JAR="trading-simulator-0.0.1-SNAPSHOT.jar"

# 日志目录统一（避免日志散落在各bin目录）
LOG_BASE_DIR="/home/group10/software/logs"
mkdir -p "${LOG_BASE_DIR}"  # 确保日志目录存在

# 颜色输出（和停止脚本统一，便于区分状态）
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== 开始启动所有服务 ===${NC}"

# 1. 启动 Redis（增加启动前检查，避免重复启动）
echo -n "--- 1. 启动 Redis："
if "${REDIS_SERVER}" --version > /dev/null 2>&1; then
  # 检查Redis是否已运行
  if ! /home/group10/software/redis-7.0.14/redis/bin/redis-cli ping > /dev/null 2>&1; then
    mkdir -p "${REDIS_DIR}/data"
    "${REDIS_SERVER}" --daemonize yes --dir "${REDIS_DIR}/data"
    sleep 1
    # 验证启动结果
    if /home/group10/software/redis-7.0.14/redis/bin/redis-cli ping > /dev/null 2>&1; then
      echo -e "${GREEN}成功${NC}"
    else
      echo -e "${RED}失败${NC}"
      exit 1  # Redis启动失败，终止脚本（核心依赖）
    fi
  else
    echo -e "${YELLOW}已运行，跳过${NC}"
  fi
else
  echo -e "${RED}Redis可执行文件不存在${NC}"
  exit 1
fi

# 2. 启动 MySQL（最终通用版：适配任何路径/软链接，彻底避免误判）
echo -n "--- 2. 启动 MySQL："
MYSQLD_SAFE="${MYSQL_DIR}/bin/mysqld_safe"
MYSQL_BIN="${MYSQL_DIR}/bin/mysql"

# 1. 检查基础文件
if [ ! -f "${MYSQLD_SAFE}" ]; then
  echo -e "${RED}mysqld_safe不存在${NC}"
  exit 1
fi

# 2. 检查是否已运行（最通用：只看用户+关键词，不看路径）
if ! pgrep -u group10 -f "mysqld" > /dev/null 2>&1; then
  # 3. 启动 MySQL
  "${MYSQLD_SAFE}" --defaults-file="${MYSQL_CNF}" > "${LOG_BASE_DIR}/mysql.log" 2>&1 &
  echo -n "（启动中，等待5秒）"
  sleep 5

  # 4. 最终验证：只要 group10 的 mysqld 进程在，就成功
  if pgrep -u group10 -f "mysqld" > /dev/null 2>&1; then
    echo -e "${GREEN}成功${NC}"
  else
    echo -e "${RED}失败，请查看真实错误日志：/home/group10/software/mysql/data/VM-0-3-rockylinux.err${NC}"
    exit 1
  fi
else
  echo -e "${YELLOW}已运行，跳过${NC}"
fi

# 3. 启动 Prometheus（修正路径，日志统一，增加验证）
echo -n "--- 3. 启动 Prometheus："
PROMETHEUS_BIN="${PROMETHEUS_DIR}/prometheus"
PROMETHEUS_LOG="${LOG_BASE_DIR}/prometheus.log"
if [ -f "${PROMETHEUS_BIN}" ]; then
  # 检查Prometheus是否已运行
  if ! pgrep -f "${PROMETHEUS_BIN}" > /dev/null 2>&1; then
    cd "${PROMETHEUS_DIR}" || { echo -e "${RED}目录不存在${NC}"; exit 1; }
    nohup "${PROMETHEUS_BIN}" --config.file=prometheus.yml > "${PROMETHEUS_LOG}" 2>&1 &
    sleep 1
    if pgrep -f "${PROMETHEUS_BIN}" > /dev/null 2>&1; then
      echo -e "${GREEN}成功${NC}"
    else
      echo -e "${RED}失败${NC}"
    fi
  else
    echo -e "${YELLOW}已运行，跳过${NC}"
  fi
else
  echo -e "${RED}Prometheus可执行文件不存在${NC}"
fi

# 4. 启动 MySQL Exporter（修正路径，日志统一，增加验证）
echo -n "--- 4. 启动 MySQL Exporter："
EXPORTER_BIN="${MYSQL_EXPORTER_DIR}/mysqld_exporter"
EXPORTER_LOG="${LOG_BASE_DIR}/mysqld_exporter.log"
if [ -f "${EXPORTER_BIN}" ]; then
  # 检查Exporter是否已运行
  if ! pgrep -f "${EXPORTER_BIN}" > /dev/null 2>&1; then
    cd "${MYSQL_EXPORTER_DIR}" || { echo -e "${RED}目录不存在${NC}"; exit 1; }
    nohup "${EXPORTER_BIN}" --config.my-cnf=.my.cnf > "${EXPORTER_LOG}" 2>&1 &
    sleep 1
    if pgrep -f "${EXPORTER_BIN}" > /dev/null 2>&1; then
      echo -e "${GREEN}成功${NC}"
    else
      echo -e "${RED}失败${NC}"
    fi
  else
    echo -e "${YELLOW}已运行，跳过${NC}"
  fi
else
  echo -e "${RED}mysqld_exporter可执行文件不存在${NC}"
fi

# 5. 启动 Grafana（优化版：兼容正则+端口双重验证，100%准确）
echo -n "--- 5. 启动 Grafana："
GRAFANA_BIN="${GRAFANA_DIR}/bin/grafana-server"
GRAFANA_LOG="${LOG_BASE_DIR}/grafana.log"
GRAFANA_PORT=3000  # Grafana 默认端口

if [ -f "${GRAFANA_BIN}" ]; then
  # 先检查是否已运行（用简单关键词 + -E 扩展正则，兼容所有系统）
  if ! pgrep -u group10 -f "grafana" > /dev/null 2>&1; then
    cd "${GRAFANA_DIR}/bin" || { echo -e "${RED}目录不存在${NC}"; exit 1; }
    nohup "${GRAFANA_BIN}" > "${GRAFANA_LOG}" 2>&1 &
    echo -n "（启动中，等待3秒）"
    sleep 3  # 多等1秒，给Grafana足够时间启动

    # 双重验证：进程 + 端口（更可靠）
    if pgrep -u group10 -f "grafana" > /dev/null 2>&1 && ss -tulpn | grep -q ":${GRAFANA_PORT}"; then
      echo -e "${GREEN}成功${NC}"
    else
      echo -e "${RED}失败，请查看日志：${GRAFANA_LOG}${NC}"
    fi
  else
    echo -e "${YELLOW}已运行，跳过${NC}"
  fi
else
  echo -e "${RED}grafana-server可执行文件不存在${NC}"
fi

# 6. 启动 Spring Boot 应用（修正日志路径，增加验证）
echo -n "--- 6. 启动 Spring Boot 应用："
SPRING_BOOT_LOG="${LOG_BASE_DIR}/spring_boot.log"
if [ -f "${SPRING_BOOT_DIR}/${SPRING_BOOT_JAR}" ]; then
  # 检查应用是否已运行
  if ! pgrep -f "${SPRING_BOOT_JAR}" > /dev/null 2>&1; then
    cd "${SPRING_BOOT_DIR}" || { echo -e "${RED}目录不存在${NC}"; exit 1; }
    nohup java -jar "${SPRING_BOOT_JAR}" > "${SPRING_BOOT_LOG}" 2>&1 &
    sleep 2  # 应用启动稍慢，等待2秒
    if pgrep -f "${SPRING_BOOT_JAR}" > /dev/null 2>&1; then
      echo -e "${GREEN}成功${NC}"
    else
      echo -e "${RED}失败，请查看日志：${SPRING_BOOT_LOG}${NC}"
    fi
  else
    echo -e "${YELLOW}已运行，跳过${NC}"
  fi
else
  echo -e "${RED}Spring Boot JAR包不存在${NC}"
fi

echo -e "${GREEN}=== 所有服务启动流程执行完毕！===${NC}"
echo -e "${YELLOW}日志查看路径（统一管理）：${NC}"
echo "- Redis：默认日志（可在redis.conf中配置到${LOG_BASE_DIR}/redis.log）"
echo "- MySQL：${LOG_BASE_DIR}/mysql.log"
echo "- Prometheus：${LOG_BASE_DIR}/prometheus.log"
echo "- MySQL Exporter：${LOG_BASE_DIR}/mysqld_exporter.log"
echo "- Grafana：${LOG_BASE_DIR}/grafana.log"
echo "- Spring Boot 应用：${LOG_BASE_DIR}/spring_boot.log"