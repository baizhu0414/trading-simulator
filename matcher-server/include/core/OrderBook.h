#pragma once

#include <map>
#include <vector>
#include <string>
#include <mutex>
#include <queue>
#include "model/Order.h"
#include "model/Trade.h"

namespace matcher {
namespace core {

class OrderBook {
public:
    OrderBook() = default;

    // 将订单提交到订单簿并尝试撮合，返回产生的成交记录
    std::vector<matcher::model::Trade> submit(const matcher::model::Order& order);

    // 撤单
    bool cancel(const std::string& clOrderId);

    // 返回订单簿当前未成交订单快照
    std::vector<matcher::model::Order> snapshot() const;

    // 接口：添加订单到订单簿（不撮合）
    void addOrder(const matcher::model::Order& order);

    // 接口：获取指定方向的价格Map引用（买：价格降序，卖：价格升序）
    std::map<double, std::vector<matcher::model::Order>, std::greater<double>>& getBuyPriceMap();
    std::map<double, std::vector<matcher::model::Order>>& getSellPriceMap();

    // 接口：移除订单
    bool removeOrder(const matcher::model::Order& order);

private:
    // 价格 -> orders. buy: price desc, sell: price asc
    std::map<double, std::vector<matcher::model::Order>, std::greater<double>> buyOrders_;
    std::map<double, std::vector<matcher::model::Order>> sellOrders_;

    mutable std::mutex mutex_;

    // 内部撮合实现：撮合一个新订单并生成成交
    std::vector<matcher::model::Trade> matchAgainst(matcher::model::Order order);
};

} // namespace core
} // namespace matcher
