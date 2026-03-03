@echo off
chcp 936 > nul  # 改用中文GBK编码，彻底解决乱码（Windows CMD默认编码）
title 监控组件一键启动脚本
color 0A

:: ====================== 你的路径（无需修改） ======================
set ROOT_DIR=D:\Software\springbootMonitor
set EXPORTER_DIR=%ROOT_DIR%\mysqld_exporter-0.18.0.windows-amd64
set PROMETHEUS_DIR=%ROOT_DIR%\prometheus-3.9.1.windows-amd64
set GRAFANA_DIR=%ROOT_DIR%\grafana-10.2.0\bin
:: =====================================================================

echo ====================== 开始启动监控组件 ======================
echo 启动顺序：mysqld_exporter → Prometheus → Grafana
echo **********************************************************

:: 第一步：启动mysqld_exporter（用ping做延时，兼容所有Windows）
echo [1/3] 启动 mysqld_exporter...
if not exist "%EXPORTER_DIR%\mysqld_exporter.exe" (
    echo [错误] 未找到 mysqld_exporter.exe，路径：%EXPORTER_DIR%
    pause
    exit /b 1
)
if not exist "%EXPORTER_DIR%\.my.cnf" (
    echo [错误] 未找到 .my.cnf 配置文件，路径：%EXPORTER_DIR%
    pause
    exit /b 1
)
start "MySQL Exporter" cmd /k "cd /d %EXPORTER_DIR% && mysqld_exporter.exe --config.my-cnf=.my.cnf"
echo [信息] mysqld_exporter 启动窗口已打开，等待5秒...
ping -n 6 127.0.0.1 > nul  # ping 6次（约5秒），替代timeout，无兼容问题

:: 第二步：启动Prometheus
echo [2/3] 启动 Prometheus...
if not exist "%PROMETHEUS_DIR%\prometheus.exe" (
    echo [错误] 未找到 prometheus.exe，路径：%PROMETHEUS_DIR%
    pause
    exit /b 1
)
if not exist "%PROMETHEUS_DIR%\prometheus.yml" (
    echo [错误] 未找到 prometheus.yml 配置文件，路径：%PROMETHEUS_DIR%
    pause
    exit /b 1
)
start "Prometheus" cmd /k "cd /d %PROMETHEUS_DIR% && prometheus.exe --config.file=prometheus.yml"
echo [信息] Prometheus 启动窗口已打开，等待8秒...
ping -n 9 127.0.0.1 > nul  # ping 9次（约8秒）

:: 第三步：启动Grafana
echo [3/3] 启动 Grafana...
if not exist "%GRAFANA_DIR%\grafana-server.exe" (
    echo [错误] 未找到 grafana-server.exe，路径：%GRAFANA_DIR%
    pause
    exit /b 1
)
start "Grafana" cmd /k "cd /d %GRAFANA_DIR% && grafana-server.exe"
echo [信息] Grafana 启动窗口已打开...

echo **********************************************************
echo ====================== 启动完成验证 ======================
echo 1. 验证 mysqld_exporter：访问 http://localhost:9104/metrics
echo 2. 验证 Prometheus：访问 http://localhost:9090/targets（查看mysql是否UP）
echo 3. 验证 Grafana：访问 http://localhost:3000（默认账号/密码：admin/admin）
echo 注意：关闭各窗口即可停止对应组件
pause