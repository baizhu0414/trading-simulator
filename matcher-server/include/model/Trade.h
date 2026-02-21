#pragma once

#include <string>
#include <cstdint>
#include <chrono>

namespace matcher {
namespace model {

/**
 * @brief 成交记录结构体
 * 
 * 对应Java端的Trade数据结构，用于表示一次成交。
 */
struct Trade {
    std::string tradeId;          ///< 成交ID
    std::string clOrderIdBuy;     ///< 买方订单客户端ID
    std::string clOrderIdSell;    ///< 卖方订单客户端ID
    std::string securityId;       ///< 证券代码
    double price;                 ///< 成交价格
    int64_t quantity;             ///< 成交数量
    std::chrono::system_clock::time_point timestamp; ///< 成交时间戳
    
    // 构造函数
    Trade() = default;
    Trade(std::string tradeId, std::string clOrderIdBuy, std::string clOrderIdSell,
          std::string securityId, double price, int64_t quantity,
          std::chrono::system_clock::time_point timestamp)
        : tradeId(std::move(tradeId))
        , clOrderIdBuy(std::move(clOrderIdBuy))
        , clOrderIdSell(std::move(clOrderIdSell))
        , securityId(std::move(securityId))
        , price(price)
        , quantity(quantity)
        , timestamp(timestamp) {}
};

} // namespace model
} // namespace matcher