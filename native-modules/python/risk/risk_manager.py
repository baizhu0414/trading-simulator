import logging
from typing import Dict, Optional

logger = logging.getLogger(__name__)

class RiskManager:
    """
    风控管理器
    目前主要实现对敲风控（Self-Trade Prevention）：
    检测同一股东号在同一支股票上是否存在反向交易（即一买一卖）。
    """
    def __init__(self):
        # 内存缓存：key = shareholderId_securityId, value = side (BUY/SELL)
        # 注意：这是一个简化的内存实现，重启后数据丢失。
        # 生产环境可能需要 Redis。
        self._self_trade_cache: Dict[str, str] = {}

    def check(self, order_data: dict) -> dict:
        """
        检查订单是否符合风控规则
        :param order_data: 订单数据，需包含 shareholderId, securityId, side
        :return: {"passed": bool, "message": str}
        """
        shareholder_id = order_data.get("shareholderId")
        security_id = order_data.get("securityId")
        side = order_data.get("side")
        order_id = order_data.get("clOrderId", "unknown")

        if not shareholder_id or not security_id or not side:
            return {"passed": False, "message": "Missing required fields for risk check"}

        cache_key = f"{shareholder_id}_{security_id}"
        existing_side = self._self_trade_cache.get(cache_key)

        # 1. 缓存中无该股东号-股票的订单 -> 通过并记录
        if existing_side is None:
            self._self_trade_cache[cache_key] = side
            logger.info(f"Order {order_id} passed risk check (First order for {cache_key})")
            return {"passed": True, "message": "Passed"}

        # 2. 缓存中有相反方向订单 -> 拒绝（对敲）
        if existing_side != side:
            msg = f"Self-trade detected for {shareholder_id} on {security_id}. Existing: {existing_side}, New: {side}"
            logger.warning(f"Order {order_id} rejected: {msg}")
            return {"passed": False, "message": msg}

        # 3. 同方向订单 -> 通过（不更新缓存或更新均可，这里保持原样）
        # 如果是同方向，说明是单纯的加仓/减仓，不构成对敲
        logger.info(f"Order {order_id} passed risk check (Same side {side} for {cache_key})")
        return {"passed": True, "message": "Passed"}

    def reset(self):
        """重置内部状态（用于测试）"""
        self._self_trade_cache.clear()
