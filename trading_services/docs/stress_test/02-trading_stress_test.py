# -*- coding: utf-8 -*-
import time
import random
import logging
import threading  # 新增：导入线程锁模块
from locust import HttpUser, task, between

# ================= 核心配置 =================
HOST = "http://129.211.187.179:8081"
TEST_PRICE_BASE = 10.016185
PRICE_DELTA = 0.0005

# 拆分买卖方股东号（10位，符合数据库约束）
FIX_BUYER_SHAREHOLDER = "SH00000001"
FIX_SELLER_SHAREHOLDER = "SH00000002"
FIX_MARKET = "XSHG"
FIX_SECURITY = "600030"

# 核心订单序列
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

# ================= Locust 用户类 =================
class TradingCoreUser(HttpUser):
    host = HOST
    wait_time = between(0.001, 0.002)

    # 订单ID生成器（关键修改：替换为线程锁）
    _order_seq = 0
    _seq_lock = threading.Lock()  # 关键修改：用线程锁替代random.Random()

    def _get_next_order_id(self, side_prefix: str) -> str:
        """生成16位唯一订单ID（修复锁机制）"""
        # 正确使用线程锁：保证高并发下_seq自增的原子性
        with self._seq_lock:
            TradingCoreUser._order_seq = (TradingCoreUser._order_seq + 1) % 1000000
            seq = TradingCoreUser._order_seq
        
        # 纳秒级时间戳 + 序列号，保证唯一性
        ts_ns = time.time_ns()
        raw_id = f"{side_prefix}{ts_ns}{seq:06d}"
        return raw_id[:16] if len(raw_id) >= 16 else (raw_id + "0"*(16-len(raw_id)))

    def _make_order_payload(self, side: str, qty: int) -> dict:
        """构造下单请求体（根据方向选择股东号）"""
        cl_order_id = self._get_next_order_id(side)
        price = round(TEST_PRICE_BASE + (PRICE_DELTA if side == "B" else -PRICE_DELTA), 2)
        
        # 买卖方股东号分离
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
        """核心任务：循环发送订单+撤销指定S单"""
        for loop_idx in range(LOOP_TIMES):
            if loop_idx % 10 == 0:  # 从100改为10
                logging.info(f"循环进度：{loop_idx}/{LOOP_TIMES}")

            for side, qty, need_cancel in CORE_ORDER_SEQUENCE:
                # 构造并发送下单请求
                order_payload = self._make_order_payload(side, qty)
                try:
                    response = self.client.post(
                        "/trading/api/trading/order",
                        json=order_payload,
                        timeout=10,
                        name="/trading/order (core sequence)"
                    )
                    if response.status_code != 200:
                        logging.error(
                            f"下单失败 | 循环{loop_idx} | {side}{qty} | 股东号={order_payload['shareholderId']} "
                            f"| 状态码={response.status_code} | 响应={response.text}"
                        )
                except Exception as e:
                    logging.error(
                        f"下单异常 | 循环{loop_idx} | {side}{qty} | 股东号={order_payload['shareholderId']} "
                        f"| 错误={str(e)}"
                    )
                    continue

                # 撤销标记的S单剩余部分
                if need_cancel and side == "S":
                    cancel_payload = self._make_cancel_payload(order_payload)
                    try:
                        cancel_response = self.client.post(
                            "/trading/api/trading/cancel",
                            json=cancel_payload,
                            timeout=10,
                            name="/trading/cancel (S 500剩余部分)"
                        )
                        if cancel_response.status_code != 200:
                            logging.warning(
                                f"撤单失败 | 原单ID={order_payload['clOrderId']} | 股东号={cancel_payload['shareholderId']} "
                                f"| 状态码={cancel_response.status_code} | 响应={cancel_response.text}"
                            )
                    except Exception as e:
                        logging.error(
                            f"撤单异常 | 原单ID={order_payload['clOrderId']} | 股东号={cancel_payload['shareholderId']} "
                            f"| 错误={str(e)}"
                        )
                        
"""
轻量调试命令:
locust -f 02-trading_stress_test.py --headless -u 10 -r 2 -t 20s

正式压测命令（高并发场景）:
locust -f 02-trading_stress_test.py --headless -u 200 -r 50 -t 300s

"""