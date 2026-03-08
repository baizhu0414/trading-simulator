#pragma once

#include <string>
#include <cstdint>
#include <chrono>

namespace matcher {
namespace model {

/**
 * @brief 订单结构体
 * 
 * 完全对应 Java 端的 Order 模型和 t_exchange_order 表结构
 * 同时兼容 IPC 通信协议 (protocol/order.schema.json)
 */
struct Order {
    // 核心字段 (对应 Java 端 Order 类和数据库表)
    std::string clOrderId;        ///< 订单唯一编号 (16位)
    std::string shareholderId;    ///< 股东号 (10位)
    std::string market;           ///< 交易市场 (XSHG/XSHE/BJSE, 4位)
    std::string securityId;       ///< 证券代码 (6位)
    std::string side;             ///< 买卖方向 ("B" or "S", 1位)
    int qty;                      ///< 当前订单数量 (对应数据库 qty 字段)
    int originalQty;              ///< 原始订单数量 (对应数据库 original_qty 字段)
    double price;                 ///< 订单价格 (DECIMAL(10,2))
    std::string status;           ///< 订单状态 (NEW/MATCHING/PART_FILLED/FULL_FILLED/CANCELLED/REJECTED)
    std::chrono::system_clock::time_point timestamp; ///< 订单时间戳
    
    // 扩展字段 (用于撮合引擎内部)
    int64_t executedQuantity = 0;     ///< 已成交数量 (撮合引擎使用)
    double lastExecutedPrice = 0.0;   ///< 最后成交价格 (撮合引擎使用)
    std::string orderType;            ///< 订单类型 (LIMIT/MARKET等)
    std::string timeInForce;          ///< 有效期类型 (GTC/IOC/FOK等)
    
    // 构造函数
    Order() : qty(0), originalQty(0), price(0.0) {}
    
    /**
     * @brief 完整构造函数
     */
    Order(std::string clOrderId, std::string shareholderId, std::string market,
          std::string securityId, std::string side, int qty, int originalQty,
          double price, std::string status, 
          std::chrono::system_clock::time_point timestamp = std::chrono::system_clock::now())
        : clOrderId(std::move(clOrderId))
        , shareholderId(std::move(shareholderId))
        , market(std::move(market))
        , securityId(std::move(securityId))
        , side(std::move(side))
        , qty(qty)
        , originalQty(originalQty)
        , price(price)
        , status(std::move(status))
        , timestamp(timestamp) {}
    
    /**
     * @brief 简化构造函数 (用于测试)
     */
    Order(std::string clOrderId, std::string securityId, std::string side,
          int qty, double price, std::string status = "NEW")
        : clOrderId(std::move(clOrderId))
        , securityId(std::move(securityId))
        , side(std::move(side))
        , qty(qty)
        , originalQty(qty)
        , price(price)
        , status(std::move(status))
        , timestamp(std::chrono::system_clock::now()) {}
};

} // namespace model
} // namespace matcher
