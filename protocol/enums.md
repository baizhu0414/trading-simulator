# Protocol Enums

## Side
- BUY
- SELL

## OrderStatus(新增字段)
    NEW "新建订单"
    VALID "校验通过"
    RISK_REJECT "风控拦截"
    MATCHING "撮合中"
    PART_FILLED "部分成交"
    FULL_FILLED "完全成交"
    CANCELLED "已撤单"
    REJECTED "非法订单"

## Market
- XSHG   # 上交所
- XSHE   # 深交所
- BJSE   # 北交所
