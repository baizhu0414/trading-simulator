#pragma once

#include <memory>
#include <string>
#include <unordered_map>
#include <mutex>
#include <vector>
#include "core/IMatchingEngine.h"
#include "persistence/IPersistence.h"
#include "ipc/IPCServer.h"
#include "model/Order.h"
#include "model/Trade.h"
#include "core/OrderBook.h"

namespace matcher {
namespace core {

class MatchingEngine : public IMatchingEngine {
public:
    MatchingEngine(std::unique_ptr<persistence::IPersistence> persistence, ipc::IPCServer* ipcServer);
    ~MatchingEngine() override;

    void submitOrder(const matcher::model::Order& order) override;
    bool cancelOrder(const std::string& clOrderId) override;
    std::vector<matcher::model::Trade> matchBatch(
        const std::string& market,
        const std::string& securityId,
        const std::vector<matcher::model::Order>& buyOrders,
        const std::vector<matcher::model::Order>& sellOrders) override;
    std::vector<matcher::model::Order> getOrderBook(const std::string& securityId) const override;

private:
    std::unique_ptr<persistence::IPersistence> persistence_;
    ipc::IPCServer* ipcServer_ = nullptr;

    // one orderbook per security
    std::unordered_map<std::string, std::unique_ptr<OrderBook>> books_;
    mutable std::recursive_mutex engineMutex_;

    OrderBook* getOrCreateBook(const std::string& securityId);

    // 撮合逻辑核心方法
    std::vector<matcher::model::Trade> matchOrder(matcher::model::Order& order);
    
};

// 工厂函数实现将在 cpp 中提供

} // namespace core
} // namespace matcher
