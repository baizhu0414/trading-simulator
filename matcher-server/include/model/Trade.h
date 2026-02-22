#pragma once

#include <string>
#include <cstdint>
#include <chrono>

namespace matcher {
namespace model {

/**
 * @brief 成交记录结构体
 * 
 * 完全对应 Java 端的 t_exchange_trade 表结构
 */
struct Trade {
    std::string tradeId;              ///< 成交ID (对应 exec_id, 12位)
    std::string clOrderIdBuy;         ///< 买方订单ID (对应 buy_cl_order_id, 16位)
    std::string clOrderIdSell;        ///< 卖方订单ID (对应 sell_cl_order_id, 16位)
    std::string securityId;           ///< 证券代码 (6位)
    double price;                     ///< 成交价格 (对应 exec_price)
    int64_t quantity;                 ///< 成交数量 (对应 exec_qty)
    std::chrono::system_clock::time_point timestamp; ///< 成交时间戳 (对应 trade_time)
    
    // 新增字段 (与 Java 端 t_exchange_trade 表对齐)
    std::string market;               ///< 交易市场 (XSHG/XSHE/BJSE, 4位)
    std::string buyShareholderId;     ///< 买方股东号 (10位)
    std::string sellShareholderId;    ///< 卖方股东号 (10位)
    
    // 构造函数
    Trade() = default;
    
    Trade(std::string tradeId, std::string clOrderIdBuy, std::string clOrderIdSell,
          std::string securityId, double price, int64_t quantity,
          std::chrono::system_clock::time_point timestamp,
          std::string market = "", std::string buyShareholderId = "", 
          std::string sellShareholderId = "")
        : tradeId(std::move(tradeId))
        , clOrderIdBuy(std::move(clOrderIdBuy))
        , clOrderIdSell(std::move(clOrderIdSell))
        , securityId(std::move(securityId))
        , price(price)
        , quantity(quantity)
        , timestamp(timestamp)
        , market(std::move(market))
        , buyShareholderId(std::move(buyShareholderId))
        , sellShareholderId(std::move(sellShareholderId)) {}
};

} // namespace model
} // namespace matcher
