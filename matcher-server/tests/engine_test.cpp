#include <iostream>
#include <memory>
#include <thread>
#include "core/IMatchingEngine.h"
#include "model/Order.h"
#include "model/Trade.h"
#include "persistence/IPersistence.h"
#include "util/Logger.h"

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
    // 初始化日志（写入 logs/）
    matcher::util::Logger::init("");

    auto persistence = std::make_unique<MockPersistence>();
    // create engine with no IPC server
    auto engine = core::createMatchingEngine(std::move(persistence), nullptr);

    // 1) 非法订单（数量<=0）
    std::cout << "Test1: invalid qty -> should be rejected" << std::endl;
    Order bad("B_BAD", "600100", "B", 0, 1.0);
    try { engine->submitOrder(bad); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // 2) 简单完全成交
    std::cout << "Test2: simple full match" << std::endl;
    Order buy("B_TEST_1", "600000", "B", 10, 100.0);
    Order sell("S_TEST_1", "600000", "S", 10, 100.0);
    try { std::cout << " - submit buy" << std::endl; engine->submitOrder(buy); std::cout << " - buy submitted" << std::endl; }
    catch (const std::exception &e) { std::cout << "Exception on buy: " << e.what() << std::endl; }
    try { std::cout << " - submit sell" << std::endl; engine->submitOrder(sell); std::cout << " - sell submitted" << std::endl; }
    catch (const std::exception &e) { std::cout << "Exception on sell: " << e.what() << std::endl; }
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // 3) 部分成交和时间优先
    std::cout << "Test3: time priority and partial fills" << std::endl;
    // two sell orders at same price
    Order sell1("S_TP_1", "600002", "S", 3, 50.0);
    Order sell2("S_TP_2", "600002", "S", 5, 50.0);
    try { engine->submitOrder(sell1); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    try { engine->submitOrder(sell2); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    // buy that consumes first then part of second
    Order buyTp("B_TP_1", "600002", "B", 6, 50.0);
    try { engine->submitOrder(buyTp); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // 4) 价格优先
    std::cout << "Test4: price priority" << std::endl;
    // sells at 101 and 100
    Order sellA("S_PP_1", "600003", "S", 2, 101.0);
    Order sellB("S_PP_2", "600003", "S", 2, 100.0);
    try { engine->submitOrder(sellA); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    try { engine->submitOrder(sellB); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    // buy at 101 should match price=100 first
    Order buyPp("B_PP_1", "600003", "B", 4, 101.0);
    try { engine->submitOrder(buyPp); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // 5) 撤单测试
    std::cout << "Test5: cancel remaining order" << std::endl;
    Order buyC("B_CANCEL", "600004", "B", 5, 10.0);
    try { engine->submitOrder(buyC); } catch (const std::exception &e) { std::cout << "Exception: " << e.what() << std::endl; }
    bool cancelled = false;
    try { cancelled = engine->cancelOrder("B_CANCEL"); } catch (const std::exception &e) { std::cout << "Exception on cancel: " << e.what() << std::endl; }
    std::cout << "Cancel result: " << (cancelled ? "success" : "failed") << std::endl;
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // 6) 批量撮合
    std::cout << "Test6: batch match" << std::endl;
    std::vector<Order> buys;
    std::vector<Order> sells;
    buys.emplace_back("B_BATCH_1", "600001", "B", 5, 10.0);
    sells.emplace_back("S_BATCH_1", "600001", "S", 5, 10.0);
    auto trades = engine->matchBatch("XSHG", "600001", buys, sells);
    std::cout << "Batch match produced " << trades.size() << " trades" << std::endl;

    // 结束日志
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    matcher::util::Logger::shutdown();

    std::cout << "All tests done" << std::endl;
    return 0;
}
