import argparse
import csv
import datetime
import json
import os
import re
import sys

import pymysql


SUPPORTED_TABLES = {
    "t_exchange_order": "orders",
    "t_exchange_trade": "trades",
}

TABLE_TIME_COLUMNS = {
    "t_exchange_order": "create_time",
    "t_exchange_trade": "trade_time",
}


def parse_args():
    parser = argparse.ArgumentParser(description="Export trading history to CSV files.")
    parser.add_argument("--output-dir", default="data", help="Directory to store exported CSV files.")
    parser.add_argument("--host", default=os.getenv("TRADING_DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("TRADING_DB_PORT", "10001")))
    parser.add_argument("--user", default=os.getenv("TRADING_DB_USER", "root"))
    parser.add_argument("--password", default=os.getenv("TRADING_DB_PASSWORD", "root"))
    parser.add_argument("--database", default=os.getenv("TRADING_DB_NAME", "trading_db"))
    parser.add_argument(
        "--tables",
        nargs="+",
        default=list(SUPPORTED_TABLES.keys()),
        help="Tables to export, default exports both order/trade tables.",
    )
    parser.add_argument(
        "--start-time",
        default="",
        help="Filter start time (inclusive). Supported formats: YYYY-MM-DD, YYYY-MM-DD HH:MM:SS, YYYY-MM-DDTHH:MM:SS",
    )
    parser.add_argument(
        "--end-time",
        default="",
        help="Filter end time (inclusive). Supported formats: YYYY-MM-DD, YYYY-MM-DD HH:MM:SS, YYYY-MM-DDTHH:MM:SS",
    )
    parser.add_argument(
        "--time-columns",
        nargs="*",
        default=[],
        help=(
            "Override default time columns by table. Format: table=column. "
            "Example: t_exchange_order=create_time t_exchange_trade=trade_time"
        ),
    )
    parser.add_argument(
        "--columns",
        nargs="*",
        default=[],
        help=(
            "Select export columns by table. Format: table=col1,col2,col3. "
            "If omitted for a table, exports all columns."
        ),
    )
    return parser.parse_args()


def build_db_config(args):
    return {
        "host": args.host,
        "port": args.port,
        "user": args.user,
        "password": args.password,
        "database": args.database,
        "cursorclass": pymysql.cursors.DictCursor,
    }


def parse_datetime(value, is_end=False):
    value = (value or "").strip()
    if not value:
        return None

    formats = [
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d",
    ]
    for fmt in formats:
        try:
            parsed = datetime.datetime.strptime(value, fmt)
            if fmt == "%Y-%m-%d" and is_end:
                parsed = parsed.replace(hour=23, minute=59, second=59)
            return parsed
        except ValueError:
            continue
    raise ValueError(f"Invalid datetime format: {value}")


def parse_table_mapping(items, arg_name):
    mapping = {}
    for item in items:
        raw = item.strip()
        if not raw:
            continue
        if "=" not in raw:
            raise ValueError(f"Invalid {arg_name} item: {raw}. Expected format table=value")

        table_name, value = raw.split("=", 1)
        table_name = table_name.strip()
        value = value.strip()

        if table_name not in SUPPORTED_TABLES:
            raise ValueError(
                f"Unsupported table in {arg_name}: {table_name}. "
                f"Supported tables: {', '.join(SUPPORTED_TABLES.keys())}"
            )
        if not value:
            raise ValueError(f"Empty value in {arg_name}: {raw}")
        mapping[table_name] = value
    return mapping


def parse_column_mapping(items):
    raw_mapping = parse_table_mapping(items, "--columns")
    columns_mapping = {}

    for table_name, value in raw_mapping.items():
        columns = [col.strip() for col in value.split(",") if col.strip()]
        if not columns:
            raise ValueError(f"No valid columns provided for table: {table_name}")
        columns_mapping[table_name] = columns

    return columns_mapping


def validate_identifier(name, kind):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
        raise ValueError(f"Invalid {kind}: {name}")


def fetch_table_columns(cursor, table_name):
    cursor.execute(f"SHOW COLUMNS FROM {table_name}")
    rows = cursor.fetchall()
    return [row["Field"] for row in rows]


def resolve_time_column(table_name, time_override):
    if table_name in time_override:
        time_column = time_override[table_name]
    else:
        time_column = TABLE_TIME_COLUMNS.get(table_name, "")
    if time_column:
        validate_identifier(time_column, "time column")
    return time_column


def resolve_selected_columns(table_name, requested_columns, available_columns):
    if table_name not in requested_columns:
        return available_columns

    selected = requested_columns[table_name]
    available_set = set(available_columns)
    invalid_columns = [col for col in selected if col not in available_set]
    if invalid_columns:
        raise ValueError(
            f"Invalid columns for {table_name}: {', '.join(invalid_columns)}. "
            f"Available columns: {', '.join(available_columns)}"
        )
    return selected


def export_table(
    cursor,
    table_name,
    output_file,
    selected_columns,
    time_column,
    start_time,
    end_time,
):
    validate_identifier(table_name, "table name")
    for column_name in selected_columns:
        validate_identifier(column_name, "column name")

    sql = f"SELECT {', '.join(selected_columns)} FROM {table_name}"
    sql_params = []

    if (start_time or end_time) and not time_column:
        raise ValueError(f"No time column configured for table: {table_name}")

    if start_time or end_time:
        clauses = []
        if start_time:
            clauses.append(f"{time_column} >= %s")
            sql_params.append(start_time.strftime("%Y-%m-%d %H:%M:%S"))
        if end_time:
            clauses.append(f"{time_column} <= %s")
            sql_params.append(end_time.strftime("%Y-%m-%d %H:%M:%S"))
        sql += " WHERE " + " AND ".join(clauses)

    cursor.execute(sql, sql_params)
    rows = cursor.fetchall()

    fieldnames = selected_columns
    with open(output_file, "w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=fieldnames)
        writer.writeheader()
        if rows:
            writer.writerows(rows)
    return len(rows)


def main():
    args = parse_args()
    db_config = build_db_config(args)

    try:
        start_time = parse_datetime(args.start_time, is_end=False)
        end_time = parse_datetime(args.end_time, is_end=True)
        time_override = parse_table_mapping(args.time_columns, "--time-columns")
        selected_columns = parse_column_mapping(args.columns)
    except ValueError as err:
        print(f"Argument error: {err}")
        return 1

    if start_time and end_time and start_time > end_time:
        print("Argument error: --start-time cannot be later than --end-time")
        return 1

    os.makedirs(args.output_dir, exist_ok=True)
    timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")

    selected_tables = []
    for table_name in args.tables:
        if table_name not in SUPPORTED_TABLES:
            print(f"Unsupported table: {table_name}")
            print(f"Supported tables: {', '.join(SUPPORTED_TABLES.keys())}")
            return 1
        selected_tables.append(table_name)

    manifest = {
        "export_time": datetime.datetime.now().isoformat(),
        "database": args.database,
        "host": args.host,
        "filters": {
            "start_time": start_time.strftime("%Y-%m-%d %H:%M:%S") if start_time else "",
            "end_time": end_time.strftime("%Y-%m-%d %H:%M:%S") if end_time else "",
        },
        "tables": {},
    }

    try:
        conn = pymysql.connect(**db_config)
    except Exception as err:
        print(f"Failed to connect database: {err}")
        return 1

    try:
        with conn.cursor() as cursor:
            for table_name in selected_tables:
                available_columns = fetch_table_columns(cursor, table_name)
                table_columns = resolve_selected_columns(table_name, selected_columns, available_columns)
                time_column = resolve_time_column(table_name, time_override)

                prefix = SUPPORTED_TABLES[table_name]
                output_file = os.path.join(args.output_dir, f"{prefix}_{timestamp}.csv")
                row_count = export_table(
                    cursor,
                    table_name,
                    output_file,
                    table_columns,
                    time_column,
                    start_time,
                    end_time,
                )

                manifest["tables"][table_name] = {
                    "file": output_file,
                    "rows": row_count,
                    "columns": table_columns,
                    "time_column": time_column,
                }
                print(f"Exported {row_count} rows from {table_name} -> {output_file}")
    except Exception as err:
        print(f"Export failed: {err}")
        return 1
    finally:
        conn.close()

    manifest_file = os.path.join(args.output_dir, f"manifest_{timestamp}.json")
    with open(manifest_file, "w", encoding="utf-8") as mf:
        json.dump(manifest, mf, ensure_ascii=False, indent=2)

    print(f"Manifest written to {manifest_file}")
    print("History storage complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
