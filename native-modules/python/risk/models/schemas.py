"""
数据模型定义
严格对应 protocol/ 目录中的 JSON Schema 定义：
- protocol/order.schema.json          → Order
- protocol/ipc/risk_check_request.schema.json  → RiskCheckRequest
- protocol/ipc/risk_check_response.schema.json → RiskCheckResponse
- protocol/enums.md                   → SideEnum, OrderStatusEnum, MarketEnum

注意：protocol/order.schema.json 中 side 的枚举值为 "B"/"S"，
但 protocol/examples/ipc/ 和 Java SideEnum 中使用的是 "BUY"/"SELL"，
本服务同时兼容两种格式。
"""

from pydantic import BaseModel, Field, field_validator
from typing import List, Optional
from enum import Enum


class SideEnum(str, Enum):
    """
    买卖方向枚举
    对应 protocol/enums.md → Side、Java SideEnum
    兼容 order.schema.json 中的 "B"/"S" 和 IPC 示例中的 "BUY"/"SELL"
    """
    BUY = "BUY"
    SELL = "SELL"


class OrderStatusEnum(str, Enum):
    """
    订单状态枚举
    对应 protocol/enums.md → OrderStatus、Java OrderStatusEnum
    """
    NEW = "NEW"
    VALID = "VALID"
    RISK_REJECT = "RISK_REJECT"
    MATCHING = "MATCHING"
    PART_FILLED = "PART_FILLED"
    FULL_FILLED = "FULL_FILLED"
    CANCELLED = "CANCELLED"
    REJECTED = "REJECTED"


class MarketEnum(str, Enum):
    """
    交易市场枚举
    对应 protocol/enums.md → Market、Java Market 字段
    """
    XSHG = "XSHG"  # 上交所
    XSHE = "XSHE"  # 深交所
    BJSE = "BJSE"   # 北交所


class Order(BaseModel):
    """
    订单模型
    对应 protocol/order.schema.json
    对应 Java 侧 com.example.trading.domain.model.Order
    """
    clOrderId: str = Field(..., max_length=16, description="订单唯一编号")
    shareholderId: str = Field(..., max_length=10, description="股东号")
    market: MarketEnum = Field(..., description="交易市场 XSHG/XSHE/BJSE")
    securityId: str = Field(..., max_length=6, description="股票代码")
    side: SideEnum = Field(..., description="买卖方向 BUY/SELL")
    qty: int = Field(..., ge=1, description="订单数量（>0）")
    price: float = Field(..., ge=0, description="订单价格（>=0）")
    status: Optional[OrderStatusEnum] = Field(None, description="订单状态")
    timestamp: Optional[int] = Field(None, description="订单提交时间戳（毫秒）")

    @field_validator("side", mode="before")
    @classmethod
    def normalize_side(cls, v: str) -> str:
        """兼容 order.schema.json 中的 B/S 和 IPC 示例中的 BUY/SELL"""
        mapping = {"B": "BUY", "S": "SELL"}
        return mapping.get(v, v)


class RiskCheckRequest(BaseModel):
    """
    风控检查请求
    对应 protocol/ipc/risk_check_request.schema.json
    示例见 protocol/examples/ipc/risk_check_request.json
    """
    incomingOrder: Order = Field(..., description="待检查的新订单")
    existingOrders: List[Order] = Field(..., description="订单簿中已有的活跃订单")


class RiskCheckResponse(BaseModel):
    """
    风控检查响应
    对应 protocol/ipc/risk_check_response.schema.json
    示例见 protocol/examples/ipc/risk_check_response.json
    """
    allow: bool = Field(..., description="是否允许订单进入撮合")
    reason: Optional[str] = Field(None, description="拒绝原因（allow=false 时填写）")
