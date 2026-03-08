"""
对敲风控检查器
检测同一股东号在同一股票上的自买自卖行为

与 Java 侧 SelfTradeChecker 的关系：
- Java SelfTradeChecker 基于内存 ConcurrentHashMap 做简单方向缓存判断
- Python 版为增强实现：基于 Java 传入的 existingOrders 做精确逐笔匹配
- Java 侧通过 IPC 调用本服务，将订单簿中的活跃订单一并传入

对敲判定条件（全部满足则拦截）：
  1. 同一股东号（shareholderId）
  2. 同一股票（securityId）
  3. 同一市场（market）
  4. 相反买卖方向（BUY vs SELL）
  5. 价格可成交（买价 >= 卖价）
  6. 在时间窗口内（可配置，默认 60s）
"""

import logging
from typing import Optional, Tuple

from models.schemas import Order, RiskCheckRequest, RiskCheckResponse, SideEnum
from config import SELF_TRADE_TIME_WINDOW_MS, SELF_TRADE_ENABLE

logger = logging.getLogger(__name__)


class SelfTradeChecker:
    """对敲风控检查器"""

    def __init__(
        self,
        enable: bool = SELF_TRADE_ENABLE,
        time_window_ms: int = SELF_TRADE_TIME_WINDOW_MS,
    ):
        self.enable = enable
        self.time_window_ms = time_window_ms
        logger.info(
            "SelfTradeChecker 初始化 | enable=%s | time_window=%dms",
            self.enable,
            self.time_window_ms,
        )

    def check(self, request: RiskCheckRequest) -> RiskCheckResponse:
        """
        执行对敲风控检查

        Args:
            request: 风控检查请求（对应 protocol/ipc/risk_check_request.schema.json）

        Returns:
            RiskCheckResponse（对应 protocol/ipc/risk_check_response.schema.json）
        """
        if not self.enable:
            logger.info(
                "对敲风控已关闭，订单 [%s] 直接放行",
                request.incomingOrder.clOrderId,
            )
            return RiskCheckResponse(allow=True, reason=None)

        incoming = request.incomingOrder

        logger.info(
            "开始对敲风控检查 | 订单[%s] 股东号[%s] 股票[%s] 方向[%s] "
            "价格[%.2f] 数量[%d] | 已有订单数=%d",
            incoming.clOrderId,
            incoming.shareholderId,
            incoming.securityId,
            incoming.side.value,
            incoming.price,
            incoming.qty,
            len(request.existingOrders),
        )

        is_self_trade, matched = self._detect(incoming, request.existingOrders)

        if is_self_trade and matched is not None:
            reason = "SELF_TRADE_DETECTED"
            logger.warning(
                "对敲风控拦截 | 新订单[%s](%s) ↔ 已有订单[%s](%s) | "
                "股东号[%s] 股票[%s]",
                incoming.clOrderId,
                incoming.side.value,
                matched.clOrderId,
                matched.side.value,
                incoming.shareholderId,
                incoming.securityId,
            )
            return RiskCheckResponse(allow=False, reason=reason)

        logger.info("对敲风控通过 | 订单[%s]", incoming.clOrderId)
        return RiskCheckResponse(allow=True, reason=None)

    # ── 私有方法 ──────────────────────────────────────────────

    def _detect(
        self,
        incoming: Order,
        existing_orders: list[Order],
    ) -> Tuple[bool, Optional[Order]]:
        """遍历已有订单，逐笔匹配对敲条件"""
        for existing in existing_orders:
            if self._is_self_trade(incoming, existing):
                return True, existing
        return False, None

    def _is_self_trade(self, incoming: Order, existing: Order) -> bool:
        """判定两笔订单是否构成对敲"""
        # 1) 同一股东号
        if existing.shareholderId != incoming.shareholderId:
            return False
        # 2) 同一股票
        if existing.securityId != incoming.securityId:
            return False
        # 3) 同一市场
        if existing.market != incoming.market:
            return False
        # 4) 相反方向
        if existing.side == incoming.side:
            return False
        # 5) 价格可成交（买价 >= 卖价）
        if not self._price_crossable(incoming, existing):
            return False
        # 6) 时间窗口
        if not self._within_time_window(incoming, existing):
            return False
        return True

    @staticmethod
    def _price_crossable(incoming: Order, existing: Order) -> bool:
        """买价 >= 卖价 → 可成交"""
        if incoming.side == SideEnum.BUY:
            return incoming.price >= existing.price
        else:
            return existing.price >= incoming.price

    def _within_time_window(self, incoming: Order, existing: Order) -> bool:
        """
        时间窗口检查
        如果任一订单缺少时间戳，保守策略：视为在窗口内
        """
        if incoming.timestamp is None or existing.timestamp is None:
            return True
        return abs(incoming.timestamp - existing.timestamp) <= self.time_window_ms
