import json
import logging
import os
import subprocess
from datetime import datetime
from glob import glob
from zipfile import ZIP_DEFLATED, ZipFile

import requests
import pymysql
from flask import Flask, render_template, request, jsonify, send_file

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_ANALYSIS_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data_analysis"))
HISTORY_STORAGE_SCRIPT = os.path.join(DATA_ANALYSIS_DIR, "history_storage.py")
EXPORT_OUTPUT_DIR = os.path.join(DATA_ANALYSIS_DIR, "data")
EXPORT_PACKAGE_DIR = os.path.join(EXPORT_OUTPUT_DIR, "packages")


@app.after_request
def add_no_cache_headers(response):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    return response

order_history = []

@app.route('/api/clear', methods=['POST'])
def clear_memory():
    global order_history
    order_history.clear()
    return jsonify({'status': 'ok'})

DB_CONFIG = {
    'host': '127.0.0.1',
    'port': 10001,
    'user': 'root',
    'password': 'root',
    'database': 'trading_db',
    'cursorclass': pymysql.cursors.DictCursor
}

@app.route('/api/stats', methods=['GET'])
def get_stats():
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            cursor.execute("SELECT status, COUNT(*) as cnt FROM t_exchange_order GROUP BY status")
            rows = cursor.fetchall()
        conn.close()

        # DB status code mapping:
        # 0: PROCESSING, 1: RISK_REJECT, 2: MATCHING, 3: NOT_FILLED,
        # 4: PART_FILLED, 5: CANCELED, 6: FULL_FILLED, 7: REJECTED
        total = sum(int(r['cnt']) for r in rows)
        matching = sum(int(r['cnt']) for r in rows if str(r['status']) in ['0', '2', '3'])
        filled = sum(int(r['cnt']) for r in rows if str(r['status']) in ['4', '6'])
        rejected = sum(int(r['cnt']) for r in rows if str(r['status']) in ['1', '5', '7'])

        return jsonify({
            "total": total,
            "matching": matching,
            "filled": filled,
            "rejected": rejected,
            "scope": "db"
        })
    except Exception as e:
        app.logger.error(f"Failed to query stats from MySQL: {e}")
        return jsonify({"error": str(e)}), 500

JAVA_BACKEND_URL = "http://127.0.0.1:8081/trading/api/trading/order"
JAVA_BACKEND_CANCEL_URL = "http://127.0.0.1:8081/trading/api/trading/cancel"


def persist_rejected_order_to_db(req_data):
    """Persist a rejected order into DB so KPI remains DB-consistent."""
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO t_exchange_order
                (cl_order_id, shareholder_id, market, security_id, side, qty, original_qty, price, status)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, '1')
                ON DUPLICATE KEY UPDATE
                    shareholder_id = VALUES(shareholder_id),
                    market = VALUES(market),
                    security_id = VALUES(security_id),
                    side = VALUES(side),
                    qty = VALUES(qty),
                    original_qty = VALUES(original_qty),
                    price = VALUES(price),
                    status = '1'
                """,
                (
                    req_data.get("clOrderId"),
                    req_data.get("shareholderId"),
                    req_data.get("market"),
                    req_data.get("securityId"),
                    req_data.get("side"),
                    int(req_data.get("qty", 0)),
                    int(req_data.get("qty", 0)),
                    float(req_data.get("price", 0)),
                ),
            )
        conn.commit()
        conn.close()
    except Exception as e:
        app.logger.error(f"Failed to persist rejected order into DB: {e}")

def get_status_str(status_val):
    status_map = {
        0: "PROCESSING", 1: "RISK_REJECT", 2: "MATCHING", 
        3: "NOT_FILLED", 4: "PART_FILLED", 5: "CANCELED", 
        6: "FULL_FILLED", 7: "REJECTED"
    }
    try:
        val = int(status_val)
        return status_map.get(val, "UNKNOWN")
    except:
        return str(status_val)

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/api/orders", methods=["GET"])
def get_orders():
    res = []
    page = request.args.get("page", default=1, type=int)
    size = request.args.get("size", default=200, type=int)
    statuses_raw = request.args.get("statuses", default="", type=str)
    keyword = (request.args.get("keyword", default="", type=str) or "").strip()

    if page is None or page < 1:
        page = 1
    if size is None or size < 1:
        size = 200
    if size > 1000:
        size = 1000

    offset = (page - 1) * size
    db_total = 0
    selected_statuses = []
    if statuses_raw:
        for token in statuses_raw.split(","):
            token = token.strip()
            if token.isdigit():
                val = int(token)
                if 0 <= val <= 7:
                    selected_statuses.append(val)
    # Keep deterministic order and remove duplicates.
    selected_statuses = sorted(set(selected_statuses))

    where_clause = ""
    where_params = []
    if selected_statuses:
        placeholders = ",".join(["%s"] * len(selected_statuses))
        where_clause = f" WHERE status IN ({placeholders})"
        where_params = selected_statuses

    if keyword:
        search_clause = (
            "(cl_order_id LIKE %s OR security_id LIKE %s OR shareholder_id LIKE %s "
            "OR market LIKE %s OR side LIKE %s)"
        )
        kw = f"%{keyword}%"
        if where_clause:
            where_clause += " AND " + search_clause
        else:
            where_clause = " WHERE " + search_clause
        where_params.extend([kw, kw, kw, kw, kw])

    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT COUNT(*) AS total FROM t_exchange_order" + where_clause,
                tuple(where_params),
            )
            db_total = cursor.fetchone()["total"]
            cursor.execute(
                "SELECT cl_order_id, security_id, market, side, price, qty, original_qty, shareholder_id, status "
                "FROM t_exchange_order"
                + where_clause
                + " ORDER BY id DESC LIMIT %s OFFSET %s",
                tuple(where_params + [size, offset]),
            )
            rows = cursor.fetchall()
            
        for r in rows:
            res.append({
                "request": {
                    "clOrderId": r['cl_order_id'],
                    "securityId": r['security_id'],
                    "market": r['market'],
                    "side": r['side'],
                    "price": float(r['price']),
                    "qty": r['original_qty'],
                    "shareholderId": r['shareholder_id'],
                    "statusCode": int(r['status']) if str(r['status']).isdigit() else None,
                },
                "status": get_status_str(r['status']),
                "response": {}
            })
        conn.close()
    except Exception as e:
        app.logger.error(f"Failed to query MySQL: {e}")

    total = db_total
    total_pages = (total + size - 1) // size if size > 0 else 1
    if total_pages < 1:
        total_pages = 1

    return jsonify({
        "total": total,
        "page": page,
        "size": size,
        "totalPages": total_pages,
        "orders": res,
    })


@app.route("/api/cancel", methods=["POST"])
def cancel_order():
    data = request.json or {}
    try:
        app.logger.info(f"Sending cancel to backend: {data}")
        response = requests.post(JAVA_BACKEND_CANCEL_URL, json=data, timeout=5)
        if response.status_code == 200:
            result = response.json()
            return jsonify(result), 200

        return jsonify({"error": "Backend error", "details": response.text}), response.status_code
    except requests.exceptions.RequestException as e:
        app.logger.error(f"Connection to cancel backend failed: {e}")
        return jsonify({"error": "Failed to connect to trading backend", "details": str(e)}), 500

@app.route("/api/order", methods=["POST"])
def submit_order():
    data = request.json
    try:
        app.logger.info(f"Sending order to backend: {data}")
        response = requests.post(JAVA_BACKEND_URL, json=data, timeout=5)
        
        if response.status_code == 200:
            result = response.json()
            code = str(result.get("code", ""))
            is_reject = bool(
                result.get("rejectCode")
                or result.get("rejectText")
                or (code and code not in {"0000", "2000"})
            )

            # Some risk/business rejections (e.g. 3011 self-trade risk) are not persisted by backend.
            # Persist them here so right-side KPI remains consistent with DB totals.
            if is_reject:
                persist_rejected_order_to_db(data)

            order_record = {
                "request": data,
                "response": result,
                "status": result.get("orderStatus", result.get("msg", "Unknown")),
                "success": not is_reject,
            }
            order_history.append(order_record)
            if len(order_history) > 5000:
                order_history.pop(0)
            return jsonify(result), 200
        else:
            error_text = response.text
            app.logger.error(f"Backend failed with {response.status_code}: {error_text}")
            order_record = {
                "request": data,
                "response": error_text,
                "status": "HTTP_ERROR",
                "success": False
            }
            order_history.append(order_record)
            if len(order_history) > 5000:
                order_history.pop(0)
            return jsonify({"error": "Backend error", "details": error_text}), response.status_code
    except requests.exceptions.RequestException as e:
        app.logger.error(f"Connection to backend failed: {e}")
        order_record = {
            "request": data,
            "response": str(e),
            "status": "CONNECTION_FAILED",
            "success": False
        }
        order_history.append(order_record)
        if len(order_history) > 5000:
            order_history.pop(0)
        return jsonify({"error": "Failed to connect to trading backend", "details": str(e)}), 500


def _normalize_export_date(value, is_end=False):
    if value is None:
        return ""

    text = str(value).strip()
    if not text:
        return ""

    date_formats = ["%Y-%m-%d", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"]
    for fmt in date_formats:
        try:
            parsed = datetime.strptime(text, fmt)
            if fmt == "%Y-%m-%d" and is_end:
                parsed = parsed.replace(hour=23, minute=59, second=59)
            return parsed.strftime("%Y-%m-%d %H:%M:%S")
        except ValueError:
            continue

    raise ValueError(f"Invalid date format: {text}")


def _parse_columns(text):
    raw = str(text or "").strip()
    if not raw:
        return []
    return [col.strip() for col in raw.split(",") if col.strip()]


def _latest_manifest_path():
    pattern = os.path.join(EXPORT_OUTPUT_DIR, "manifest_*.json")
    files = glob(pattern)
    if not files:
        return ""
    return max(files, key=os.path.getmtime)


def _safe_join_export_output(file_path):
    normalized = os.path.abspath(file_path)
    export_root = os.path.abspath(EXPORT_OUTPUT_DIR)
    if not normalized.startswith(export_root + os.sep) and normalized != export_root:
        raise ValueError("Invalid export file path")
    return normalized


def _build_export_package(manifest_path, manifest_data):
    os.makedirs(EXPORT_PACKAGE_DIR, exist_ok=True)

    stamp = datetime.now().strftime("%Y%m%d%H%M%S")
    package_name = f"history_export_{stamp}.zip"
    package_path = os.path.join(EXPORT_PACKAGE_DIR, package_name)

    with ZipFile(package_path, "w", ZIP_DEFLATED) as zf:
        if manifest_path and os.path.exists(manifest_path):
            zf.write(manifest_path, arcname=os.path.basename(manifest_path))

        tables = (manifest_data or {}).get("tables", {})
        for table_name in tables:
            table_meta = tables.get(table_name) or {}
            csv_path = table_meta.get("file", "")
            if not csv_path:
                continue
            try:
                safe_csv_path = _safe_join_export_output(csv_path)
            except ValueError:
                continue
            if os.path.exists(safe_csv_path):
                zf.write(safe_csv_path, arcname=os.path.basename(safe_csv_path))

    return package_name, package_path


@app.route("/api/export-field-options", methods=["GET"])
def export_field_options():
    result = {
        "t_exchange_order": [],
        "t_exchange_trade": [],
    }
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            for table_name in result.keys():
                cursor.execute(f"SHOW COLUMNS FROM {table_name}")
                rows = cursor.fetchall()
                result[table_name] = [row.get("Field") for row in rows if row.get("Field")]
        conn.close()
    except Exception as err:
        app.logger.error(f"Failed to load export field options: {err}")
        return jsonify({"error": str(err)}), 500

    return jsonify({"tables": result})


@app.route("/api/export-history-csv", methods=["POST"])
def export_history_csv():
    payload = request.json or {}

    include_orders = bool(payload.get("includeOrders", True))
    include_trades = bool(payload.get("includeTrades", True))
    if not include_orders and not include_trades:
        return jsonify({"error": "At least one table must be selected."}), 400

    try:
        start_time = _normalize_export_date(payload.get("startDate", ""), is_end=False)
        end_time = _normalize_export_date(payload.get("endDate", ""), is_end=True)
    except ValueError as err:
        return jsonify({"error": str(err)}), 400

    if start_time and end_time and start_time > end_time:
        return jsonify({"error": "startDate cannot be later than endDate."}), 400

    if not os.path.exists(HISTORY_STORAGE_SCRIPT):
        return jsonify({"error": f"Missing script: {HISTORY_STORAGE_SCRIPT}"}), 500

    os.makedirs(EXPORT_OUTPUT_DIR, exist_ok=True)

    tables = []
    columns_args = []
    if include_orders:
        tables.append("t_exchange_order")
        order_columns = _parse_columns(payload.get("orderColumns", ""))
        if order_columns:
            columns_args.append("t_exchange_order=" + ",".join(order_columns))

    if include_trades:
        tables.append("t_exchange_trade")
        trade_columns = _parse_columns(payload.get("tradeColumns", ""))
        if trade_columns:
            columns_args.append("t_exchange_trade=" + ",".join(trade_columns))

    cmd = [
        "python3",
        HISTORY_STORAGE_SCRIPT,
        "--output-dir",
        EXPORT_OUTPUT_DIR,
        "--tables",
        *tables,
    ]

    if start_time:
        cmd.extend(["--start-time", start_time])
    if end_time:
        cmd.extend(["--end-time", end_time])
    if columns_args:
        cmd.append("--columns")
        cmd.extend(columns_args)

    try:
        proc = subprocess.run(
            cmd,
            cwd=DATA_ANALYSIS_DIR,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return jsonify({"error": "Export timeout. Please try a smaller range."}), 504
    except Exception as err:
        app.logger.error(f"Export execution failed: {err}")
        return jsonify({"error": f"Failed to execute export: {err}"}), 500

    if proc.returncode != 0:
        stderr = (proc.stderr or "").strip()
        stdout = (proc.stdout or "").strip()
        return jsonify({
            "error": "Export command failed.",
            "details": stderr or stdout,
        }), 500

    manifest_path = _latest_manifest_path()
    manifest_data = {}
    if manifest_path:
        try:
            with open(manifest_path, "r", encoding="utf-8") as mf:
                manifest_data = json.load(mf)
        except Exception as err:
            app.logger.warning(f"Failed to read manifest: {err}")

    package_name = ""
    package_path = ""
    try:
        package_name, package_path = _build_export_package(manifest_path, manifest_data)
    except Exception as err:
        app.logger.error(f"Failed to build export package: {err}")
        return jsonify({"error": f"Export succeeded but package build failed: {err}"}), 500

    download_url = f"/api/export-download/{package_name}"

    return jsonify({
        "status": "ok",
        "message": "CSV export completed.",
        "manifestFile": manifest_path,
        "manifest": manifest_data,
        "packageFile": package_path,
        "downloadUrl": download_url,
        "stdout": (proc.stdout or "").strip(),
    })


@app.route("/api/export-download/<path:package_name>", methods=["GET"])
def export_download(package_name):
    # Only allow direct filename under package folder.
    pure_name = os.path.basename(package_name)
    if pure_name != package_name or not pure_name.lower().endswith(".zip"):
        return jsonify({"error": "Invalid package name"}), 400

    file_path = os.path.join(EXPORT_PACKAGE_DIR, pure_name)
    if not os.path.exists(file_path):
        return jsonify({"error": "Package not found"}), 404

    return send_file(
        file_path,
        as_attachment=True,
        download_name=pure_name,
        mimetype="application/zip",
    )

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
