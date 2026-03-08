"""
对敲风控单元测试 & 集成测试

测试数据参考：
  - protocol/examples/ipc/risk_check_request.json
  - protocol/examples/ipc/risk_check_response.json
"""

import sys
import os

# 确保能从 tests/ 目录导入项目模块
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from fastapi.testclient import TestClient

from main import app
from models.schemas import (
    Order,
    SideEnum,
    MarketEnum,
    OrderStatusEnum,
    RiskCheckRequest,
)
from services.self_trade_checker import SelfTradeChecker


# ════════════════════════════════════════════════════════════
# 辅助函数
# ════════════════════════════════════════════════════════════


def make_order(
    cl_order_id: str = "ORD001",
    shareholder_id: str = "SH1",
    side: SideEnum = SideEnum.BUY,
    price: float = 10.5,
    qty: int = 100,
    security_id: str = "600030",
    market: MarketEnum = MarketEnum.XSHG,
    timestamp: int = 1700000000000,
) -> Order:
    return Order(
        clOrderId=cl_order_id,
        shareholderId=shareholder_id,
        market=market,
        securityId=security_id,
        side=side,
        qty=qty,
        price=price,
        status=OrderStatusEnum.NEW,
        timestamp=timestamp,
    )


# ════════════════════════════════════════════════════════════
# 单元测试：SelfTradeChecker 核心逻辑
# ════════════════════════════════════════════════════════════


class TestSelfTradeChecker:

    def setup_method(self):
        self.checker = SelfTradeChecker(enable=True, time_window_ms=60000)

    # ── 基本对敲检测 ──────────────────────────────────────

    def test_self_trade_detected(self):
        """同一股东号、同一股票、反方向、同价格 → 拦截"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B2", "SH1", SideEnum.BUY, 10.5, timestamp=1700000000200),
            existingOrders=[make_order("S2", "SH1", SideEnum.SELL, 10.5, timestamp=1700000000100)],
        )
        resp = self.checker.check(req)
        assert resp.allow is False
        assert resp.reason == "SELF_TRADE_DETECTED"

    def test_different_shareholder_pass(self):
        """不同股东号 → 放行"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY),
            existingOrders=[make_order("S1", "SH2", SideEnum.SELL)],
        )
        assert self.checker.check(req).allow is True

    def test_same_side_pass(self):
        """同方向 → 放行"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY),
            existingOrders=[make_order("B2", "SH1", SideEnum.BUY)],
        )
        assert self.checker.check(req).allow is True

    def test_different_security_pass(self):
        """不同股票 → 放行"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, security_id="600030"),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, security_id="600031")],
        )
        assert self.checker.check(req).allow is True

    def test_different_market_pass(self):
        """不同市场 → 放行"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, market=MarketEnum.XSHG),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, market=MarketEnum.XSHE)],
        )
        assert self.checker.check(req).allow is True

    # ── 价格可成交性 ──────────────────────────────────────

    def test_buy_price_less_than_sell_pass(self):
        """买价 < 卖价 → 不可成交 → 放行"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, price=10.0),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, price=10.5)],
        )
        assert self.checker.check(req).allow is True

    def test_buy_price_greater_than_sell_detected(self):
        """买价 > 卖价 → 可成交 → 拦截"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, price=11.0),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, price=10.5)],
        )
        assert self.checker.check(req).allow is False

    def test_sell_incoming_detected(self):
        """新订单为卖方 + 已有订单为买方 + 买价>=卖价 → 拦截"""
        req = RiskCheckRequest(
            incomingOrder=make_order("S1", "SH1", SideEnum.SELL, price=10.0),
            existingOrders=[make_order("B1", "SH1", SideEnum.BUY, price=10.5)],
        )
        assert self.checker.check(req).allow is False

    # ── 时间窗口 ──────────────────────────────────────────

    def test_time_window_exceeded_pass(self):
        """超出 60s 时间窗口 → 放行"""
        checker = SelfTradeChecker(enable=True, time_window_ms=60000)
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, timestamp=1700000100000),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, timestamp=1700000000000)],
        )
        assert checker.check(req).allow is True

    def test_time_window_within_detected(self):
        """在 60s 时间窗口内 → 拦截"""
        checker = SelfTradeChecker(enable=True, time_window_ms=60000)
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, timestamp=1700000030000),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, timestamp=1700000000000)],
        )
        assert checker.check(req).allow is False

    def test_no_timestamp_conservative(self):
        """缺少时间戳 → 保守策略视为在窗口内 → 拦截"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, timestamp=None),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL, timestamp=None)],
        )
        assert self.checker.check(req).allow is False

    # ── 开关 & 边界 ──────────────────────────────────────

    def test_disabled_pass(self):
        """风控关闭 → 直接放行"""
        checker = SelfTradeChecker(enable=False)
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY),
            existingOrders=[make_order("S1", "SH1", SideEnum.SELL)],
        )
        assert checker.check(req).allow is True

    def test_empty_existing_orders_pass(self):
        """无已有订单 → 放行"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY),
            existingOrders=[],
        )
        assert self.checker.check(req).allow is True

    def test_multiple_orders_partial_match(self):
        """多个已有订单中只有第三个构成对敲"""
        req = RiskCheckRequest(
            incomingOrder=make_order("B1", "SH1", SideEnum.BUY, price=10.5),
            existingOrders=[
                make_order("S1", "SH2", SideEnum.SELL, price=10.5),   # 不同股东
                make_order("S2", "SH1", SideEnum.SELL, price=11.0),   # 卖价>买价
                make_order("S3", "SH1", SideEnum.SELL, price=10.3),   # 对敲！
            ],
        )
        resp = self.checker.check(req)
        assert resp.allow is False
        assert resp.reason == "SELF_TRADE_DETECTED"


# ════════════════════════════════════════════════════════════
# 单元测试：Side 字段兼容性（B/S ↔ BUY/SELL）
# ════════════════════════════════════════════════════════════


class TestSideCompatibility:
    """确保兼容 protocol/order.schema.json 的 B/S 格式"""

    def test_side_b_normalized_to_buy(self):
        order = Order(
            clOrderId="T1", shareholderId="SH1", market="XSHG",
            securityId="600030", side="B", qty=100, price=10.0,
        )
        assert order.side == SideEnum.BUY

    def test_side_s_normalized_to_sell(self):
        order = Order(
            clOrderId="T1", shareholderId="SH1", market="XSHG",
            securityId="600030", side="S", qty=100, price=10.0,
        )
        assert order.side == SideEnum.SELL

    def test_side_buy_accepted(self):
        order = Order(
            clOrderId="T1", shareholderId="SH1", market="XSHG",
            securityId="600030", side="BUY", qty=100, price=10.0,
        )
        assert order.side == SideEnum.BUY


# ════════════════════════════════════════════════════════════
# 集成测试：FastAPI HTTP 接口
# ════════════════════════════════════════════════════════════


class TestRiskAPI:

    def setup_method(self):
        self.client = TestClient(app, raise_server_exceptions=False)
        self.client.__enter__()

    def teardown_method(self):
        self.client.__exit__(None, None, None)

    def test_health_check(self):
        resp = self.client.get("/api/risk/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["service"] == "risk-service"
        assert data["port"] == 9002

    def test_risk_check_self_trade_reject(self):
        """
        对应 protocol/examples/ipc/risk_check_request.json → 拦截
        对应 protocol/examples/ipc/risk_check_response.json
        """
        body = {
            "incomingOrder": {
                "clOrderId": "B2",
                "shareholderId": "SH1",
                "market": "XSHG",
                "securityId": "600030",
                "side": "BUY",
                "qty": 100,
                "price": 10.5,
                "status": "NEW",
                "timestamp": 1700000000200,
            },
            "existingOrders": [
                {
                    "clOrderId": "S2",
                    "shareholderId": "SH1",
                    "market": "XSHG",
                    "securityId": "600030",
                    "side": "SELL",
                    "qty": 100,
                    "price": 10.5,
                    "status": "NEW",
                    "timestamp": 1700000000100,
                }
            ],
        }
        resp = self.client.post("/api/risk/check", json=body)
        assert resp.status_code == 200
        data = resp.json()
        assert data["allow"] is False
        assert data["reason"] == "SELF_TRADE_DETECTED"

    def test_risk_check_pass(self):
        """不同股东号 → 放行"""
        body = {
            "incomingOrder": {
                "clOrderId": "B1",
                "shareholderId": "SH1",
                "market": "XSHG",
                "securityId": "600030",
                "side": "BUY",
                "qty": 100,
                "price": 10.5,
                "timestamp": 1700000000000,
            },
            "existingOrders": [
                {
                    "clOrderId": "S1",
                    "shareholderId": "SH2",
                    "market": "XSHG",
                    "securityId": "600030",
                    "side": "SELL",
                    "qty": 100,
                    "price": 10.5,
                    "timestamp": 1700000000000,
                }
            ],
        }
        resp = self.client.post("/api/risk/check", json=body)
        assert resp.status_code == 200
        data = resp.json()
        assert data["allow"] is True
        assert data["reason"] is None

    def test_risk_check_with_b_s_side(self):
        """使用 B/S 格式（兼容 order.schema.json）"""
        body = {
            "incomingOrder": {
                "clOrderId": "B1",
                "shareholderId": "SH1",
                "market": "XSHG",
                "securityId": "600030",
                "side": "B",
                "qty": 100,
                "price": 10.5,
            },
            "existingOrders": [
                {
                    "clOrderId": "S1",
                    "shareholderId": "SH1",
                    "market": "XSHG",
                    "securityId": "600030",
                    "side": "S",
                    "qty": 100,
                    "price": 10.5,
                }
            ],
        }
        resp = self.client.post("/api/risk/check", json=body)
        assert resp.status_code == 200
        assert resp.json()["allow"] is False

    def test_invalid_request_422(self):
        """缺少必填字段 → 422"""
        body = {"incomingOrder": {"clOrderId": "B1"}, "existingOrders": []}
        resp = self.client.post("/api/risk/check", json=body)
        assert resp.status_code == 422
