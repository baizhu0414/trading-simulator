#include <iostream>
#include <memory>
#include "core/IMatchingEngine.h"
#include "model/Order.h"
#include "model/Trade.h"
#include "persistence/IPersistence.h"

using namespace matcher;
using namespace matcher::model;

// Simple mock persistence that prints saved trades
class MockPersistence : public persistence::IPersistence {
public:
    void saveTrade(const Trade& trade) override {
        std::cout << "MockPersistence::saveTrade - tradeId=" << trade.tradeId
                  << " security=" << trade.securityId << " qty=" << trade.quantity
                  << " price=" << trade.price << std::endl;
    }
    void updateOrder(const Order& order) override {
        std::cout << "MockPersistence::updateOrder - clOrderId=" << order.clOrderId << std::endl;
    }
    std::vector<Order> loadUnfinishedOrders(const std::string& securityId) override {
        return {};
    }
    void saveTrades(const std::vector<Trade>& trades) override {
        for (const auto &t : trades) saveTrade(t);
    }
    void updateOrders(const std::vector<Order>& orders) override {
        for (const auto &o : orders) updateOrder(o);
    }
};

int main() {
    auto persistence = std::make_unique<MockPersistence>();
    // create engine with no IPC server
    auto engine = core::createMatchingEngine(std::move(persistence), nullptr);

    // Submit a buy order then a matching sell order
    Order buy("B_TEST_1", "600000", "B", 10, 100.0);
    Order sell("S_TEST_1", "600000", "S", 10, 100.0);

    std::cout << "Submitting buy order\n";
    engine->submitOrder(buy);
    std::cout << "Submitting sell order\n";
    engine->submitOrder(sell);

    // Do a batch match test
    std::vector<Order> buys;
    std::vector<Order> sells;
    buys.emplace_back("B_BATCH_1", "600001", "B", 5, 10.0);
    sells.emplace_back("S_BATCH_1", "600001", "S", 5, 10.0);

    auto trades = engine->matchBatch("XSHG", "600001", buys, sells);
    std::cout << "Batch match produced " << trades.size() << " trades\n";

    return 0;
}
