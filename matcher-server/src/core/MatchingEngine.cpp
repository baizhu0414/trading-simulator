#include "core/MatchingEngine.h"
#include <algorithm>
#include <iostream>
#include <chrono>

namespace matcher {
namespace core {

MatchingEngine::MatchingEngine(std::unique_ptr<persistence::IPersistence> persistence, ipc::IPCServer* ipcServer)
    : persistence_(std::move(persistence)), ipcServer_(ipcServer) {}

MatchingEngine::~MatchingEngine() = default;

OrderBook* MatchingEngine::getOrCreateBook(const std::string& securityId) {
    auto it = books_.find(securityId);
    if (it != books_.end()) return it->second.get();
    auto pb = std::make_unique<OrderBook>();
    OrderBook* ptr = pb.get();
    books_.emplace(securityId, std::move(pb));
    return ptr;
}

void MatchingEngine::submitOrder(const matcher::model::Order& order) {
    std::lock_guard<std::mutex> lock(engineMutex_);
    OrderBook* book = getOrCreateBook(order.securityId);
    auto trades = book->submit(order);

    // persist and notify for each trade
    for (const auto& t : trades) {
        if (persistence_) persistence_->saveTrade(t);
        if (ipcServer_) ipcServer_->sendExecutionReport(t);
    }

    // update orders if persistence supports it (best-effort)
    // note: OrderBook currently updates quantities in-place in its structures
}

bool MatchingEngine::cancelOrder(const std::string& clOrderId) {
    std::lock_guard<std::mutex> lock(engineMutex_);
    for (auto& kv : books_) {
        if (kv.second->cancel(clOrderId)) {
            // Could call persistence_->updateOrder if needed
            return true;
        }
    }
    return false;
}

std::vector<matcher::model::Trade> MatchingEngine::matchBatch(
    const std::string& market,
    const std::string& securityId,
    const std::vector<matcher::model::Order>& buyOrders,
    const std::vector<matcher::model::Order>& sellOrders) {

    std::lock_guard<std::mutex> lock(engineMutex_);
    // Simple implementation: create a fresh temporary orderbook and feed orders
    OrderBook tmp;
    std::vector<matcher::model::Trade> trades;

    // insert all buy orders into tmp (as existing book)
    for (const auto& o : buyOrders) {
        auto t = tmp.submit(o);
        trades.insert(trades.end(), t.begin(), t.end());
    }
    for (const auto& o : sellOrders) {
        auto t = tmp.submit(o);
        trades.insert(trades.end(), t.begin(), t.end());
    }

    // persist + notify
    for (const auto& tr : trades) {
        if (persistence_) persistence_->saveTrade(tr);
        if (ipcServer_) ipcServer_->sendExecutionReport(tr);
    }

    return trades;
}

std::vector<matcher::model::Order> MatchingEngine::getOrderBook(const std::string& securityId) const {
    std::lock_guard<std::mutex> lock(engineMutex_);
    auto it = books_.find(securityId);
    if (it == books_.end()) return {};
    return it->second->snapshot();
}

// 工厂函数实现
std::unique_ptr<IMatchingEngine> createMatchingEngine(
    std::unique_ptr<persistence::IPersistence> persistence,
    ipc::IPCServer* ipcServer) {
    return std::make_unique<MatchingEngine>(std::move(persistence), ipcServer);
}

} // namespace core
} // namespace matcher
