@echo off
chcp 936 > nul
title 监控组件一键停止脚本
color 0C

echo 正在停止所有监控组件...
taskkill /f /im mysqld_exporter.exe > nul 2>&1
taskkill /f /im prometheus.exe > nul 2>&1
taskkill /f /im grafana-server.exe > nul 2>&1

echo 已停止：mysqld_exporter、Prometheus、Grafana
pause