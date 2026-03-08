# Management UI Module

## 1. 模块目标

提供交易管理控制台，支持下单、撤单、订单查询、状态统计，以及历史 CSV 导出下载。

## 2. 核心入口

- `app.py`: Flask 后端服务。
- `templates/index.html`: 前端页面。
- `start_ui.sh`: 启动脚本。

## 3. 核心功能

- 订单操作: 下单、撤单。
- 实时监控: KPI 统计、订单分页查询、状态筛选。
- 历史导出: 时间范围 + 字段多选导出。
- 浏览器下载: 导出后返回 ZIP 下载链接，外部访问者可下载到本机。

## 4. 启动方式

```bash
cd /home/group10/trading_services/management-ui
./start_ui.sh
```

访问地址:

- 本机: `http://127.0.0.1:5000`
- 外部: `http://<server-ip>:5000`

## 5. 主要接口

- `GET /api/stats`: 读取订单统计。
- `GET /api/orders`: 分页查询订单。
- `POST /api/order`: 提交订单。
- `POST /api/cancel`: 提交撤单。
- `GET /api/export-field-options`: 获取可导出字段列表。
- `POST /api/export-history-csv`: 触发导出并打包。
- `GET /api/export-download/<package>.zip`: 下载导出包。

## 6. 目录规范

已清理历史临时修复脚本（`fix_*`、`patch_*`、`*.patch`），当前目录仅保留运行所需核心文件。

## 7. 依赖

见 `requirements.txt`，当前最小依赖:

- `flask`
- `requests`

若需 MySQL 访问，请确保环境已安装 `pymysql`。
