# -*- coding: utf-8 -*-
import time
import logging
import threading
from locust import HttpUser, task, between
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# ================= TPS 场景配置（核心：修改这里切换TPS） =================
TARGET_TPS = 1000  # 可选：500 / 1000 / 1200

# ================= 核心配置 =================
HOST = "http://129.211.187.179:8081"
TEST_PRICE_BASE = 10.016185
PRICE_DELTA = 0.0005

FIX_BUYER_SHAREHOLDER = "SH00000001"
FIX_SELLER_SHAREHOLDER = "SH00000002"
FIX_MARKET = "XSHG"
FIX_SECURITY = "600030"

CORE_ORDER_SEQUENCE = [
    ("B", 100, False),
    ("S", 100, False),
    ("B", 200, False),
    ("B", 200, False),
    ("S", 500, False),
    ("B", 100, False),
    ("B", 200, False),
    ("B", 200, False),
    ("S", 500, True)
]

LOOP_TIMES = 2000

# TPS场景配置
TPS_CONFIG = {
    500: {"wait_time": (0.001, 0.002), "recommend_users": 100, "recommend_ramp": 20},
    1000: {"wait_time": (0.0005, 0.001), "recommend_users": 200, "recommend_ramp": 40},
    1200: {"wait_time": (0.0001, 0.0005), "recommend_users": 250, "recommend_ramp": 50}
}

if TARGET_TPS not in TPS_CONFIG:
    raise ValueError(f"TPS值只能是500/1000/1200，当前设置为{TARGET_TPS}")
CURRENT_TPS_CONFIG = TPS_CONFIG[TARGET_TPS]

logging.info(f"✅ 已加载TPS {TARGET_TPS} 场景配置：")
logging.info(f"  - 任务等待时间：{CURRENT_TPS_CONFIG['wait_time']}")
logging.info(f"  - 推荐并发用户数：{CURRENT_TPS_CONFIG['recommend_users']}")
logging.info(f"  - 推荐每秒启动用户数：{CURRENT_TPS_CONFIG['recommend_ramp']}")

# ================= Locust 用户类（优化高并发） =================
class TradingCoreUser(HttpUser):
    host = HOST
    wait_time = between(*CURRENT_TPS_CONFIG["wait_time"])

    # 优化1：增加连接池和重试机制（解决偶发连接失败）
    def on_start(self):
        """用户初始化：配置HTTP连接池和重试"""
        # 设置重试策略：连接失败时重试2次
        retry_strategy = Retry(
            total=2,  # 总重试次数
            backoff_factor=0.01,  # 重试间隔（0.01s → 0.02s → ...）
            status_forcelist=[429, 500, 502, 503, 504],  # 这些状态码重试
            allowed_methods=["POST"]  # 仅POST请求重试
        )
        # 配置连接池：增大连接数，适配高并发
        adapter = HTTPAdapter(
            max_retries=retry_strategy,
            pool_connections=100,  # 连接池数量
            pool_maxsize=200  # 每个主机的最大连接数
        )
        self.client.mount("http://", adapter)
        # 延长超时时间（从10s改为15s，适配高并发下的服务端响应延迟）
        self.client.timeout = 15

    # 订单ID生成器（线程安全）
    _order_seq = 0
    _seq_lock = threading.Lock()

    def _get_next_order_id(self, side_prefix: str) -> str:
        """生成16位唯一订单ID"""
        with self._seq_lock:
            TradingCoreUser._order_seq = (TradingCoreUser._order_seq + 1) % 1000000
            seq = TradingCoreUser._order_seq

        ts_ns = time.time_ns()
        raw_id = f"{side_prefix}{ts_ns}{seq:06d}"
        return raw_id[:16] if len(raw_id) >= 16 else (raw_id + "0"*(16-len(raw_id)))

    def _make_order_payload(self, side: str, qty: int) -> dict:
        """构造下单请求体"""
        cl_order_id = self._get_next_order_id(side)
        price = round(TEST_PRICE_BASE + (PRICE_DELTA if side == "B" else -PRICE_DELTA), 2)
        shareholder_id = FIX_BUYER_SHAREHOLDER if side == "B" else FIX_SELLER_SHAREHOLDER

        payload = {
            "clOrderId": cl_order_id,
            "market": FIX_MARKET,
            "securityId": FIX_SECURITY,
            "side": side,
            "price": price,
            "qty": qty,
            "original_qty": qty,
            "shareholderId": shareholder_id
        }
        return payload

    def _make_cancel_payload(self, orig_order_info: dict) -> dict:
        """构造撤单请求体"""
        return {
            "clOrderId": self._get_next_order_id("C"),
            "origClOrderId": orig_order_info["clOrderId"],
            "market": orig_order_info["market"],
            "securityId": orig_order_info["securityId"],
            "shareholderId": orig_order_info["shareholderId"],
            "side": orig_order_info["side"]
        }

    @task
    def loop_order_with_cancel(self):
        """核心任务：循环发送订单+撤销指定S单（优化日志）"""
        for loop_idx in range(LOOP_TIMES):
            if loop_idx % 10 == 0:
                logging.info(f"循环进度：{loop_idx}/{LOOP_TIMES} | 目标TPS：{TARGET_TPS}")

            for side, qty, need_cancel in CORE_ORDER_SEQUENCE:
                order_payload = self._make_order_payload(side, qty)
                try:
                    # 优化2：记录请求发送时间，排查响应延迟
                    start_time = time.time()
                    response = self.client.post(
                        "/trading/api/trading/order",
                        json=order_payload,
                        name="/trading/order (core sequence)"
                    )
                    cost_time = round((time.time() - start_time) * 1000, 2)  # 耗时（ms）

                    # 详细日志：区分不同失败场景
                    if response.status_code != 200:
                        logging.error(
                            f"❌ 下单失败 | 循环{loop_idx} | {side}{qty} | 股东号={order_payload['shareholderId']} "
                            f"| 状态码={response.status_code} | 耗时={cost_time}ms | 响应={response.text[:200]}"
                        )
                    else:
                        logging.debug(
                            f"✅ 下单成功 | 循环{loop_idx} | {side}{qty} | 订单ID={order_payload['clOrderId']} "
                            f"| 耗时={cost_time}ms"
                        )

                except Exception as e:
                    # 优化3：细分异常类型（连接超时/拒绝/其他）
                    err_type = type(e).__name__
                    logging.error(
                        f"💥 下单异常 | 循环{loop_idx} | {side}{qty} | 股东号={order_payload['shareholderId']} "
                        f"| 异常类型={err_type} | 错误详情={str(e)[:200]}"
                    )
                    continue

                # 撤单逻辑（保留）
                if need_cancel and side == "S":
                    cancel_payload = self._make_cancel_payload(order_payload)
                    try:
                        cancel_response = self.client.post(
                            "/trading/api/trading/cancel",
                            json=cancel_payload,
                            timeout=15,
                            name="/trading/cancel (S 500剩余部分)"
                        )
                        if cancel_response.status_code != 200:
                            logging.warning(
                                f"⚠️ 撤单失败 | 原单ID={order_payload['clOrderId']} | 状态码={cancel_response.status_code} "
                                f"| 响应={cancel_response.text[:200]}"
                            )
                    except Exception as e:
                        logging.error(
                            f"💥 撤单异常 | 原单ID={order_payload['clOrderId']} | 异常类型={type(e).__name__} "
                            f"| 错误详情={str(e)[:200]}"
                        )

# ================= 启动命令提示 =================
def print_start_commands():
    print("\n📌 各TPS场景推荐启动命令：")
    for tps, config in TPS_CONFIG.items():
        cmd = (
            f"locust -f 02-trading_stress_test.py --headless "
            f"-u {config['recommend_users']} -r {config['recommend_ramp']} -t 300s"
        )
        print(f"  TPS {tps}: {cmd}")

if __name__ == "__main__":
    print_start_commands()

"""
TPS1000:

locust -f 02-trading_stress_test.py --headless -u 200 -r 40 -t 300s
"""