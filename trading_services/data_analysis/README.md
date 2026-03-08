# Data Analysis Module

## 1. 模块目标

提供交易历史离线归档与分析能力，支撑对交易链路、订单状态与成交一致性的核查。

## 2. 核心入口

- `history_storage.py`: 从 MySQL 导出订单/成交 CSV。
- `history_analysis.py`: 基于导出文件生成文本和 JSON 分析报告。

## 3. 功能说明

### 3.1 历史导出

- 支持按表导出: `t_exchange_order`、`t_exchange_trade`。
- 支持时间范围过滤: `--start-time`、`--end-time`。
- 支持字段裁剪导出: `--columns table=col1,col2`。
- 自动生成 `manifest_*.json` 记录导出元数据。

### 3.2 离线分析

- 自动读取最新的 `orders_*.csv` 与 `trades_*.csv`。
- 输出 `analysis_report.txt` 和 `analysis_report.json`。
- 包含订单分布、成交统计、引用一致性、字段完整度分析。

## 4. 使用方式

基础导出:

```bash
cd /home/group10/trading_services/data_analysis
python3 history_storage.py
```

时间区间导出:

```bash
python3 history_storage.py --start-time 2025-01-01 --end-time 2025-01-31
```

字段筛选导出:

```bash
python3 history_storage.py \
  --columns t_exchange_order=cl_order_id,security_id,side,qty,price,create_time \
            t_exchange_trade=exec_id,buy_cl_order_id,sell_cl_order_id,exec_qty,exec_price,trade_time
```

生成报告:

```bash
python3 history_analysis.py
```

## 5. 输出文件

- `data/orders_*.csv`
- `data/trades_*.csv`
- `data/manifest_*.json`
- `analysis_report.txt`
- `analysis_report.json`

## 6. 维护建议

- `data/` 目录建议按周期清理历史导出包与旧 CSV，避免长期堆积。
- 建议通过定时任务执行导出和报告生成，统一保留策略。
