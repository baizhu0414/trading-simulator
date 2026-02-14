import pytest
from risk_manager import RiskManager

@pytest.fixture
def risk_manager():
    return RiskManager()

def test_first_order_passes(risk_manager):
    """测试首单通过"""
    order = {"clOrderId": "1", "shareholderId": "SH001", "securityId": "600000", "side": "BUY"}
    result = risk_manager.check(order)
    assert result["passed"] is True
    assert result["message"] == "Passed"

def test_same_side_order_passes(risk_manager):
    """测试同方向追加订单通过"""
    # 首单买入
    order1 = {"clOrderId": "1", "shareholderId": "SH001", "securityId": "600000", "side": "BUY"}
    risk_manager.check(order1)
    
    # 追单买入
    order2 = {"clOrderId": "2", "shareholderId": "SH001", "securityId": "600000", "side": "BUY"}
    result = risk_manager.check(order2)
    assert result["passed"] is True

def test_self_trade_rejected(risk_manager):
    """测试反向订单被拒绝（对敲）"""
    # 首单买入
    order1 = {"clOrderId": "1", "shareholderId": "SH001", "securityId": "600000", "side": "BUY"}
    risk_manager.check(order1)
    
    # 反向卖出 -> 拒绝
    order2 = {"clOrderId": "2", "shareholderId": "SH001", "securityId": "600000", "side": "SELL"}
    result = risk_manager.check(order2)
    assert result["passed"] is False
    assert "Self-trade detected" in result["message"]

def test_different_shareholder_passes(risk_manager):
    """测试不同股东操作互不影响"""
    # 股东1 买入
    order1 = {"clOrderId": "1", "shareholderId": "SH001", "securityId": "600000", "side": "BUY"}
    risk_manager.check(order1)
    
    # 股东2 卖出 -> 应该通过（这是正常撮合，不是对敲）
    order2 = {"clOrderId": "2", "shareholderId": "SH002", "securityId": "600000", "side": "SELL"}
    result = risk_manager.check(order2)
    assert result["passed"] is True
