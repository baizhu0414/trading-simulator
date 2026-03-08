import argparse
import csv
import glob
import json
import os
import sys
from collections import Counter


STATUS_MAP = {
    "0": "PROCESSING",
    "1": "RISK_REJECT",
    "2": "MATCHING",
    "3": "NOT_FILLED",
    "4": "PART_FILLED",
    "5": "CANCELED",
    "6": "FULL_FILLED",
    "7": "REJECTED",
}


def parse_args():
    parser = argparse.ArgumentParser(description="Analyze exported trading history files.")
    parser.add_argument("--data-dir", default="data", help="Directory containing exported CSV files.")
    parser.add_argument("--orders-file", default="", help="Explicit orders CSV path.")
    parser.add_argument("--trades-file", default="", help="Explicit trades CSV path.")
    parser.add_argument("--report-txt", default="analysis_report.txt", help="Output text report path.")
    parser.add_argument("--report-json", default="analysis_report.json", help="Output JSON report path.")
    return parser.parse_args()


def latest_file_by_pattern(pattern):
    files = glob.glob(pattern)
    if not files:
        return ""
    return max(files, key=os.path.getmtime)


def load_csv_rows(csv_file):
    rows = []
    with open(csv_file, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    return rows


def to_int(value, default=0):
    try:
        return int(value)
    except Exception:
        return default


def to_float(value, default=0.0):
    try:
        return float(value)
    except Exception:
        return default


def pct(part, total):
    if total <= 0:
        return 0.0
    return part / total


def counter_to_sorted_dict(counter_obj, top_n=0):
    items = sorted(counter_obj.items(), key=lambda x: x[1], reverse=True)
    if top_n > 0:
        items = items[:top_n]
    return {k: v for k, v in items}


def analyze_orders(orders):
    total = len(orders)
    status_counter = Counter()
    side_counter = Counter()
    market_counter = Counter()
    security_counter = Counter()
    shareholder_counter = Counter()

    total_qty = 0
    total_original_qty = 0
    total_notional = 0.0
    invalid_price_count = 0

    for order in orders:
        status_counter[str(order.get("status", "UNKNOWN"))] += 1
        side_counter[str(order.get("side", "UNKNOWN"))] += 1
        market_counter[str(order.get("market", "UNKNOWN"))] += 1
        security_counter[str(order.get("security_id", "UNKNOWN"))] += 1
        shareholder_counter[str(order.get("shareholder_id", "UNKNOWN"))] += 1

        qty = to_int(order.get("qty", 0), 0)
        orig_qty = to_int(order.get("original_qty", 0), 0)
        price = to_float(order.get("price", 0), -1)

        total_qty += qty
        total_original_qty += orig_qty
        if price < 0:
            invalid_price_count += 1
        else:
            total_notional += qty * price

    status_distribution = {}
    for code, count in sorted(status_counter.items(), key=lambda x: x[1], reverse=True):
        status_distribution[code] = {
            "name": STATUS_MAP.get(code, "UNKNOWN"),
            "count": count,
            "ratio": round(pct(count, total), 6),
        }

    return {
        "total_orders": total,
        "status_distribution": status_distribution,
        "side_distribution": counter_to_sorted_dict(side_counter),
        "market_distribution": counter_to_sorted_dict(market_counter),
        "top_securities_by_order_count": counter_to_sorted_dict(security_counter, top_n=10),
        "top_shareholders_by_order_count": counter_to_sorted_dict(shareholder_counter, top_n=10),
        "qty_sum": total_qty,
        "original_qty_sum": total_original_qty,
        "notional_sum": round(total_notional, 2),
        "avg_order_notional": round(total_notional / total, 2) if total > 0 else 0,
        "invalid_price_count": invalid_price_count,
    }


def analyze_trades(trades):
    total = len(trades)
    market_counter = Counter()
    security_trade_counter = Counter()
    security_qty_counter = Counter()
    buyer_counter = Counter()
    seller_counter = Counter()

    total_qty = 0
    total_turnover = 0.0
    max_price = None
    min_price = None

    for trade in trades:
        market = str(trade.get("market", "UNKNOWN"))
        security = str(trade.get("security_id", "UNKNOWN"))
        buyer = str(trade.get("buy_shareholder_id", "UNKNOWN"))
        seller = str(trade.get("sell_shareholder_id", "UNKNOWN"))

        market_counter[market] += 1
        security_trade_counter[security] += 1
        buyer_counter[buyer] += 1
        seller_counter[seller] += 1

        qty = to_int(trade.get("exec_qty", 0), 0)
        price = to_float(trade.get("exec_price", 0), 0)

        security_qty_counter[security] += qty
        total_qty += qty
        total_turnover += qty * price

        if max_price is None or price > max_price:
            max_price = price
        if min_price is None or price < min_price:
            min_price = price

    vwap = (total_turnover / total_qty) if total_qty > 0 else 0.0

    return {
        "total_trades": total,
        "total_exec_qty": total_qty,
        "total_turnover": round(total_turnover, 2),
        "vwap": round(vwap, 6),
        "max_exec_price": max_price if max_price is not None else 0,
        "min_exec_price": min_price if min_price is not None else 0,
        "market_distribution": counter_to_sorted_dict(market_counter),
        "top_securities_by_trade_count": counter_to_sorted_dict(security_trade_counter, top_n=10),
        "top_securities_by_trade_qty": counter_to_sorted_dict(security_qty_counter, top_n=10),
        "top_buyers_by_trade_count": counter_to_sorted_dict(buyer_counter, top_n=10),
        "top_sellers_by_trade_count": counter_to_sorted_dict(seller_counter, top_n=10),
    }


def analyze_consistency(orders, trades):
    order_id_set = set()
    for order in orders:
        cl_order_id = str(order.get("cl_order_id", "")).strip()
        if cl_order_id:
            order_id_set.add(cl_order_id)

    buy_missing = 0
    sell_missing = 0
    matched_trade_refs = 0

    for trade in trades:
        buy_id = str(trade.get("buy_cl_order_id", "")).strip()
        sell_id = str(trade.get("sell_cl_order_id", "")).strip()

        if buy_id:
            if buy_id in order_id_set:
                matched_trade_refs += 1
            else:
                buy_missing += 1
        if sell_id:
            if sell_id in order_id_set:
                matched_trade_refs += 1
            else:
                sell_missing += 1

    total_refs = (len(trades) * 2) if trades else 0
    return {
        "trade_order_reference_total": total_refs,
        "trade_order_reference_matched": matched_trade_refs,
        "buy_order_reference_missing": buy_missing,
        "sell_order_reference_missing": sell_missing,
        "reference_match_ratio": round(pct(matched_trade_refs, total_refs), 6),
    }


def analyze_interface_fields(orders, trades):
    order_fields = set(orders[0].keys()) if orders else set()
    trade_fields = set(trades[0].keys()) if trades else set()

    expected_order_fields = {
        "cl_order_id",
        "shareholder_id",
        "market",
        "security_id",
        "side",
        "qty",
        "original_qty",
        "price",
        "status",
        "create_time",
    }

    expected_trade_fields = {
        "exec_id",
        "buy_cl_order_id",
        "sell_cl_order_id",
        "exec_qty",
        "exec_price",
        "trade_time",
        "market",
        "security_id",
        "buy_shareholder_id",
        "sell_shareholder_id",
    }

    side_values = sorted({str(row.get("side", "")) for row in orders if str(row.get("side", "")).strip()})
    market_values = sorted({str(row.get("market", "")) for row in orders if str(row.get("market", "")).strip()})
    status_values = sorted({str(row.get("status", "")) for row in orders if str(row.get("status", "")).strip()})

    status_flow = []
    for status_code in status_values:
        status_flow.append({
            "code": status_code,
            "name": STATUS_MAP.get(status_code, "UNKNOWN"),
        })

    return {
        "order_fields_found": sorted(order_fields),
        "order_fields_missing": sorted(expected_order_fields - order_fields),
        "trade_fields_found": sorted(trade_fields),
        "trade_fields_missing": sorted(expected_trade_fields - trade_fields),
        "observed_side_values": side_values,
        "observed_market_values": market_values,
        "observed_status_flow": status_flow,
    }


def infer_project_understanding(summary):
    orders_total = summary["orders"]["total_orders"]
    trades_total = summary["trades"]["total_trades"]
    full_filled = summary["orders"]["status_distribution"].get("6", {}).get("count", 0)
    canceled = summary["orders"]["status_distribution"].get("5", {}).get("count", 0)
    ref_ratio = summary["consistency"]["reference_match_ratio"]

    insights = []
    insights.append("系统采用订单表与成交表分离设计，形成下单与撮合结果的双表闭环。")
    insights.append("订单主键链路是 cl_order_id，成交链路通过 buy_cl_order_id/sell_cl_order_id 回指订单。")
    insights.append("状态码体现了完整的订单生命周期，尤其是 6=FULL_FILLED 与 5=CANCELED 两种高频终态。")
    insights.append("根据成交数据可推导撮合接口包含 exec_id、exec_qty、exec_price、trade_time 等关键字段。")

    diagnostics = []
    diagnostics.append(f"订单数={orders_total}, 成交笔数={trades_total}, 全成订单数={full_filled}, 撤单数={canceled}")
    diagnostics.append(f"成交-订单引用匹配率={ref_ratio:.2%}")
    if ref_ratio < 1.0:
        diagnostics.append("存在成交引用缺失，需核查数据导出时序或订单归档策略。")
    else:
        diagnostics.append("成交引用完整，说明订单与成交链路一致性良好。")

    return {
        "insights": insights,
        "diagnostics": diagnostics,
    }


def write_text_report(report_file, summary, orders_file, trades_file):
    with open(report_file, "w", encoding="utf-8") as f:
        f.write("=== 项目交易数据离线分析报告(增强版) ===\n\n")
        f.write(f"订单数据文件: {orders_file}\n")
        f.write(f"成交数据文件: {trades_file}\n\n")

        f.write("--- 1. 订单级分析 ---\n")
        f.write(f"总订单数: {summary['orders']['total_orders']}\n")
        f.write(f"委托数量汇总(qty): {summary['orders']['qty_sum']}\n")
        f.write(f"原始委托数量汇总(original_qty): {summary['orders']['original_qty_sum']}\n")
        f.write(f"名义成交额(按订单qty*price): {summary['orders']['notional_sum']:.2f}\n")
        f.write(f"平均每笔订单名义金额: {summary['orders']['avg_order_notional']:.2f}\n")
        f.write(f"价格字段异常数量: {summary['orders']['invalid_price_count']}\n\n")

        f.write("订单状态分布:\n")
        for code, payload in summary["orders"]["status_distribution"].items():
            f.write(
                f" - {code}({payload['name']}): {payload['count']} 笔 "
                f"({payload['ratio']:.2%})\n"
            )

        f.write("\n买卖方向分布:\n")
        for side, count in summary["orders"]["side_distribution"].items():
            f.write(f" - side={side}: {count}\n")

        f.write("\n市场分布:\n")
        for market, count in summary["orders"]["market_distribution"].items():
            f.write(f" - market={market}: {count}\n")

        f.write("\n热门证券(按订单数 Top10):\n")
        for sec, count in summary["orders"]["top_securities_by_order_count"].items():
            f.write(f" - {sec}: {count}\n")

        f.write("\n--- 2. 成交级分析 ---\n")
        f.write(f"总成交笔数: {summary['trades']['total_trades']}\n")
        f.write(f"总成交量(exec_qty): {summary['trades']['total_exec_qty']}\n")
        f.write(f"总成交额(exec_qty*exec_price): {summary['trades']['total_turnover']:.2f}\n")
        f.write(f"VWAP: {summary['trades']['vwap']:.6f}\n")
        f.write(f"最高成交价: {summary['trades']['max_exec_price']}\n")
        f.write(f"最低成交价: {summary['trades']['min_exec_price']}\n\n")

        f.write("热门证券(按成交量 Top10):\n")
        for sec, qty in summary["trades"]["top_securities_by_trade_qty"].items():
            f.write(f" - {sec}: {qty}\n")

        f.write("\n--- 3. 一致性与数据质量 ---\n")
        f.write(f"成交引用总数: {summary['consistency']['trade_order_reference_total']}\n")
        f.write(f"成交引用匹配数: {summary['consistency']['trade_order_reference_matched']}\n")
        f.write(f"买单引用缺失: {summary['consistency']['buy_order_reference_missing']}\n")
        f.write(f"卖单引用缺失: {summary['consistency']['sell_order_reference_missing']}\n")
        f.write(f"引用匹配率: {summary['consistency']['reference_match_ratio']:.2%}\n\n")

        f.write("--- 4. 接口字段分析 ---\n")
        f.write(f"订单字段缺失: {summary['interfaces']['order_fields_missing']}\n")
        f.write(f"成交字段缺失: {summary['interfaces']['trade_fields_missing']}\n")
        f.write(f"观测到的 side 值: {summary['interfaces']['observed_side_values']}\n")
        f.write(f"观测到的 market 值: {summary['interfaces']['observed_market_values']}\n")
        f.write("观测到的 status 流程:\n")
        for status in summary["interfaces"]["observed_status_flow"]:
            f.write(f" - {status['code']} => {status['name']}\n")

        f.write("\n--- 5. 项目与接口理解结论 ---\n")
        for line in summary["project_understanding"]["insights"]:
            f.write(f" - {line}\n")
        f.write("诊断信息:\n")
        for line in summary["project_understanding"]["diagnostics"]:
            f.write(f" - {line}\n")


def main():
    args = parse_args()

    orders_file = args.orders_file or latest_file_by_pattern(os.path.join(args.data_dir, "orders_*.csv"))
    trades_file = args.trades_file or latest_file_by_pattern(os.path.join(args.data_dir, "trades_*.csv"))

    if not orders_file or not trades_file:
        print("No offline data found. Run history_storage.py first.")
        return 1

    print(f"Analyzing {orders_file} and {trades_file}")
    orders = load_csv_rows(orders_file)
    trades = load_csv_rows(trades_file)

    summary = {
        "input": {
            "orders_file": orders_file,
            "trades_file": trades_file,
        },
        "orders": analyze_orders(orders),
        "trades": analyze_trades(trades),
        "consistency": analyze_consistency(orders, trades),
        "interfaces": analyze_interface_fields(orders, trades),
    }
    summary["project_understanding"] = infer_project_understanding(summary)

    write_text_report(args.report_txt, summary, orders_file, trades_file)
    with open(args.report_json, "w", encoding="utf-8") as jf:
        json.dump(summary, jf, ensure_ascii=False, indent=2)

    print(f"Analysis complete. Text report: {args.report_txt}")
    print(f"Analysis complete. JSON report: {args.report_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
