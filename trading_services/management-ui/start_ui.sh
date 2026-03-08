#!/bin/bash
# 启动管理界面服务

# 进入当前目录
cd "$(dirname "$0")"

# 安装依赖
pip3 install -r requirements.txt

# 启动 flask 服务
echo "Starting Management UI on http://127.0.0.1:5000"
python3 app.py
