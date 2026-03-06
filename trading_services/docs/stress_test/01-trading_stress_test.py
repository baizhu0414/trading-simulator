import time
import json
import os
import random
from locust import HttpUser, task, between, events

# ================= 配置 =================
HOST = "http://129.211.187.179:8081"
ORDER_FILE = "order_result.json"
CANCEL_FILE = "cancel_result.json"
TEST_DURATION = 5  # 测试持续时间（秒）

# 测试数据池
STOCKS = ["600030", "600000", "000001"]
MARKETS = ["XSHG", "XSHG", "XSHE"]
NORMAL_SHAREHOLDERS = [f"SH{i:08d}" for i in range(1, 20)]

# 全局锁与计数器
FILE_LOCK = None
ORDER_COUNTER = 0
COUNTER_LOCK = None
START_TIME = None

def init_locks():
    global FILE_LOCK, COUNTER_LOCK
    import threading
    if FILE_LOCK is None:
        FILE_LOCK = threading.Lock()
    if COUNTER_LOCK is None:
        COUNTER_LOCK = threading.Lock()

# =============== [关键修改] 16位订单ID生成器 ===============
def get_next_id(side):
    global ORDER_COUNTER
    init_locks()
    with COUNTER_LOCK:
        ORDER_COUNTER += 1
        # 买=1，卖=2，撤=3
        side_num = "1" if side == "B" else "2" if side == "S" else "3"
        # 1位方向 + 15位序号 = 16位
        return f"{side_num}{ORDER_COUNTER:015d}"

def append_json(file_path, data):
    init_locks()
    with FILE_LOCK:
        results = []
        if os.path.exists(file_path):
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    results = json.load(f)
            except:
                pass
        results.append(data)
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(results, f, ensure_ascii=False, indent=2)

# ================= 测试用户类 =================
class SimpleTradingUser(HttpUser):
    wait_time = between(0.05, 0.1)  # 极短等待，快速发压
    host = HOST

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.my_orders = []

    @task(4)  # 高权重：主要发下单请求
    def place_order(self):
        if START_TIME and (time.time() - START_TIME > TEST_DURATION + 2):
            return

        idx = random.randint(0, 2)
        security_id = STOCKS[idx]
        market = MARKETS[idx]
        side = random.choice(["B", "S"])
        
        # 固定价格区间，提高成交概率
        price = round(10.00 + random.uniform(-0.2, 0.2), 2)
        qty = random.randint(100, 1000) * 100  # 整手数
        shareholder_id = random.choice(NORMAL_SHAREHOLDERS)

        # [关键修改] 获取16位ID
        cl_order_id = get_next_id(side)
        
        # 调试打印（可选，确认长度）
        # print(f"生成订单ID: {cl_order_id}, 长度: {len(cl_order_id)}")

        payload = {
            "clOrderId": cl_order_id,
            "market": market,
            "securityId": security_id,
            "side": side,
            "price": price,
            "qty": qty,
            "shareholderId": shareholder_id
        }

        try:
            response = self.client.post("/trading/api/trading/order", json=payload, timeout=5)
            
            result = {
                "timestamp": time.time(),
                "request": payload,
                "status_code": response.status_code,
                "response": response.json() if response.headers.get("content-type") == "application/json" else response.text
            }
            append_json(ORDER_FILE, result)

            if response.status_code == 200:
                self.my_orders.append(payload)

        except Exception as e:
            append_json(ORDER_FILE, {"timestamp": time.time(), "request": payload, "error": str(e)})

    @task(1)
    def cancel_order(self):
        if not self.my_orders:
            return
            
        order_to_cancel = random.choice(self.my_orders)
        # [关键修改] 撤单ID也用16位，用'C'开头
        cancel_id = get_next_id("C")

        payload = {
            "clOrderId": cancel_id,
            "origClOrderId": order_to_cancel["clOrderId"],
            "market": order_to_cancel["market"],
            "securityId": order_to_cancel["securityId"],
            "shareholderId": order_to_cancel["shareholderId"],
            "side": order_to_cancel["side"]
        }

        try:
            response = self.client.post("/trading/api/trading/cancel", json=payload, timeout=5)
            
            result = {
                "timestamp": time.time(),
                "request": payload,
                "status_code": response.status_code,
                "response": response.json() if response.headers.get("content-type") == "application/json" else response.text
            }
            append_json(CANCEL_FILE, result)
        except Exception as e:
            append_json(CANCEL_FILE, {"timestamp": time.time(), "request": payload, "error": str(e)})

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    global START_TIME
    START_TIME = time.time()
    print(f"[开始] 测试启动，目标并发数：100，持续时间：{TEST_DURATION}秒")

# ================= 运行方式说明 =================
# 请在终端使用以下命令运行：
# locust -f trading_stress_test.py --headless -u 100 -r 100 -t 10s --only-summary
#
# 参数解释：
# -u : 目标用户数（并发数）
# -r : 每秒孵化100个用户（5秒达到500）
# -t : 总共运行5秒

