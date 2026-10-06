// main.cpp
// Test harness for OrderBook (pro-rata limit order book, FIFO remainder allocation).
//
// USAGE:
//   ./test 0     -> run the CORE suite only (no cancelOrder tests)
//   ./test 1     -> run the FULL suite (core + cancelOrder tests)
//
// Behavior:
//   - Each test is its own lambda, using a fresh OrderBook instance.
//   - Every test is tagged with whether it depends on cancelOrder. Mode 0
//     runs only the untagged ("core") tests; mode 1 runs all of them.
//   - Tests run in declared order. Progress is printed and flushed BEFORE
//     each test runs, so if a test segfaults, stdout already shows which
//     test was in flight.
//   - A SIGSEGV/SIGABRT handler also prints the last known test name before
//     exiting, as a second safety net.
//   - On the first LOGICAL failure (a check() that fails), we stop
//     immediately and print how many passed.
//
// Pro-rata allocation rule (as specified by the assignment):
//   - Each resting order at the level gets floor(matchQty * theirShares / totalSharesAtLevel).
//   - This floor step under-allocates by some remainder R = matchQty - sum(floors).
//   - R is distributed 1 share at a time to the first R orders at that level,
//     where "first" means ranked by (shares descending, then timestamp/
//     submission-order ascending to break ties on equal size). E.g. 6 shares
//     left over 12 equally-sized orders means the first 6 by submission
//     order each get exactly 1 extra share (size is tied, so timestamp
//     decides) -- but if sizes differ, the largest resting order(s) get the
//     remainder first regardless of when they were submitted.
// Tests below construct exact numeric scenarios for this rule so results are
// unambiguous (no ties in the floor step itself -- only the well-specified
// size-then-timestamp remainder step is exercised).
//
// Assumptions made about OrderBook semantics beyond the pro-rata rule itself:
//   1. submitOrder(order) returns the vector of Trades produced by that single
//      call. If the order rests untouched (does not cross the opposite
//      side), it returns an empty vector.
//   2. When a marketable order only partially fills against available
//      resting liquidity, the remainder rests in the book at its own limit
//      price (standard limit-order-book behavior).
//   3. getBestBid()/getBestAsk() return -1 when that side of the book is empty.
//   4. cancelOrder(id) returns true if an order with that id was found and
//      removed, false otherwise (including calling it twice on the same id).
//   5. Side::BUY orders are bids, Side::ASK orders are asks/offers.
//   6. getTotalVolumeAtPrice(price) / getAllOrdersAtPrice(price) count BOTH
//      sides resting at that exact price (a price level is just "resting
//      quantity at that price", regardless of side).
//   7. A Trade is only emitted for resting orders that actually receive a
//      nonzero fill; resting orders whose pro-rata floor share is 0 and who
//      don't receive part of the FIFO remainder do not appear in the
//      returned Trade vector at all.

#include "Order.h"
#include "OrderBook.h"
#include "Side.h"
#include "Trade.h"

#include <algorithm>
#include <csignal>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

using namespace std;

// ---------------------------------------------------------------------
// Test-failure plumbing
// ---------------------------------------------------------------------

struct TestFailure {
    string message;
};

static void check(bool condition, const string& msg) {
    if (!condition) {
        throw TestFailure{msg};
    }
}

static void checkEqual(long actual, long expected, const string& ctx) {
    if (actual != expected) {
        throw TestFailure{ctx + " (expected " + to_string(expected) + ", got " + to_string(actual) + ")"};
    }
}

static void checkEqual(int actual, int expected, const string& ctx) {
    checkEqual((long)actual, (long)expected, ctx);
}

static void checkEqual(bool actual, bool expected, const string& ctx) {
    if (actual != expected) {
        ostringstream oss;
        oss << boolalpha << ctx << " (expected " << expected << ", got " << actual << ")";
        throw TestFailure{oss.str()};
    }
}

// ---------------------------------------------------------------------
// Construction helpers
// ---------------------------------------------------------------------

static Order makeOrder(int id, int price, int volume, Side side) {
    Order o;
    o.username = "u" + to_string(id);
    o.price = price;
    o.volume = volume;
    o.side = side;
    o.timestamp = (long)id; // ensure unique timestamp per order in tests
    return o;
}

static Trade makeTrade(int restingId, int aggressiveId, int price, int qty) {
    return Trade("u" + to_string(restingId), "u" + to_string(aggressiveId), price, qty);
}

// ---------------------------------------------------------------------
// Unordered ("set-like") comparison helpers.
//
// getAllOrders()/getAllOrdersAtPrice() and the Trade vector returned by
// submitOrder() are documented as NOT having a guaranteed order. We still
// want a one-to-one, fully-specified comparison against the expected
// collection, so both sides get sorted onto the same key before comparing
// element-by-element. order username is unique ("u<ID>"), so it's a safe
// sort key for Orders. Within a single submitOrder() call a resting order
// can only appear once in the returned Trade vector, so seller username is
// a safe sort key for Trades.
// ---------------------------------------------------------------------

static const Order* findOrder(const vector<Order>& orders, long id) {
    string uname = "u" + to_string(id);
    for (const auto& o : orders) {
        if (o.username == uname) return &o;
    }
    return nullptr;
}

static void checkOrderFields(const Order* o, long expId, long expPrice,
                             long expVolume, Side expSide, const string& ctx) {
    string uname = "u" + to_string(expId);
    check(o != nullptr, ctx + ": order " + uname + " not found");
    check(o->username == uname, ctx + ": username mismatch");
    checkEqual((long)o->price, expPrice, ctx + ": price mismatch for order " + uname);
    checkEqual((long)o->volume, expVolume, ctx + ": volume mismatch for order " + uname);
    check(o->side == expSide, ctx + ": side mismatch for order " + uname);
}

/** Asserts actual and expected contain the same orders, ignoring vector order. */
static void assertOrdersMatch(const vector<Order>& actual, const vector<Order>& expected, const string& ctx) {
    checkEqual((long)actual.size(), (long)expected.size(), ctx + ": order count mismatch");
    vector<Order> sortedExpected = expected;
    sort(sortedExpected.begin(), sortedExpected.end(),
         [](const Order& a, const Order& b) { return a.username < b.username; });
    for (const auto& e : sortedExpected) {
        // expected orders were constructed with makeOrder(id,...)
        // so the username is "u" + id
        string uname = e.username;
        // find by username
        const Order* a = nullptr;
        for (const auto& ao : actual) {
            if (ao.username == uname) { a = &ao; break; }
        }
        // parse id back from username for legacy checks
        long id = stol(uname.substr(1));
        checkOrderFields(a, id, e.price, e.volume, e.side, ctx);
    }
}

/** Asserts actual and expected contain the same trades, ignoring vector order. */
static void assertTradesMatch(const vector<Trade>& actual, const vector<Trade>& expected, const string& ctx) {
    checkEqual((long)actual.size(), (long)expected.size(), ctx + ": trade count mismatch");
    vector<Trade> sortedActual = actual;
    vector<Trade> sortedExpected = expected;
    auto bySeller = [](const Trade& a, const Trade& b) { return a.seller < b.seller; };
    sort(sortedActual.begin(), sortedActual.end(), bySeller);
    sort(sortedExpected.begin(), sortedExpected.end(), bySeller);
    for (size_t i = 0; i < sortedExpected.size(); i++) {
        const Trade& e = sortedExpected[i];
        const Trade& a = sortedActual[i];
        string tctx = ctx + ": trade vs resting seller " + e.seller;
        checkEqual(a.seller == e.seller, true, tctx + " (seller)");
        checkEqual(a.buyer == e.buyer, true, tctx + " (buyer)");
        checkEqual((long)a.tradingPrice, (long)e.tradingPrice, tctx + " (tradingPrice)");
        checkEqual((long)a.quantity, (long)e.quantity, tctx + " (quantity)");
    }
}

// ---------------------------------------------------------------------
// Test-case registration: each test is tagged with whether it needs
// cancelOrder support.
// ---------------------------------------------------------------------

struct TestCase {
    string name;
    bool requiresCancel;
    function<void()> body;
};

static vector<TestCase>& registry() {
    static vector<TestCase> instance;
    return instance;
}

static void core(const string& name, function<void()> body) {
    registry().push_back(TestCase{name, false, std::move(body)});
}

static void cancelTest(const string& name, function<void()> body) {
    registry().push_back(TestCase{name, true, std::move(body)});
}

// ---------------------------------------------------------------------
// Crash safety net
// ---------------------------------------------------------------------

static string g_currentTestName = "(not started)";
static int g_passedCount = 0;
static int g_total = 0;

static void crashHandler(int signum) {
    cerr << "\n*** CRASH (signal " << signum << ") during test \""
         << g_currentTestName << "\" ***" << endl;
    cerr << g_passedCount << "/" << g_total << " test cases passed before the crash." << endl;
    exit(139);
}

// ---------------------------------------------------------------------
// Test registration
// ---------------------------------------------------------------------

static void registerTests() {

    // ----- Empty book -----

    core("emptyBook_bestBid", [] {
        OrderBook ob;
        checkEqual(ob.getBestBid(), -1, "empty book best bid");
    });

    core("emptyBook_bestAsk", [] {
        OrderBook ob;
        checkEqual(ob.getBestAsk(), -1, "empty book best ask");
    });

    core("emptyBook_totalVolume", [] {
        OrderBook ob;
        checkEqual(ob.getTotalVolume(), 0, "empty book total volume");
    });

    core("emptyBook_getAllOrders", [] {
        OrderBook ob;
        check(ob.getAllOrders().empty(), "empty book getAllOrders should be empty");
    });

    core("emptyBook_volumeAtPrice", [] {
        OrderBook ob;
        checkEqual(ob.getTotalVolumeAtPrice(100), 0, "empty book volume at price 100");
    });

    core("emptyBook_ordersAtPrice", [] {
        OrderBook ob;
        check(ob.getAllOrdersAtPrice(100).empty(), "empty book orders at price 100 should be empty");
    });

    // ----- Single resting order -----

    core("singleRestingBuy_noTrades", [] {
        OrderBook ob;
        vector<Trade> trades = ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        check(trades.empty(), "resting buy order should produce no trades");
    });

    core("singleRestingBuy_bestBid", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.getBestBid(), 100, "best bid after single resting buy");
    });

    core("singleRestingBuy_bestAskUntouched", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.getBestAsk(), -1, "best ask should remain empty");
    });

    core("singleRestingBuy_totalVolume", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.getTotalVolume(), 10, "total volume after single resting order");
    });

    core("singleRestingBuy_volumeAtPrice", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.getTotalVolumeAtPrice(100), 10, "volume at price after resting order");
    });

    core("singleRestingBuy_ordersAtPrice", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        vector<Order> expected = {makeOrder(1, 100, 10, Side::BUY)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expected, "singleRestingBuy_ordersAtPrice");
    });

    core("singleRestingBuy_getAllOrders", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        vector<Order> expected = {makeOrder(1, 100, 10, Side::BUY)};
        assertOrdersMatch(ob.getAllOrders(), expected, "singleRestingBuy_getAllOrders");
    });

    // ----- Two-sided, non-crossing book -----

    core("nonCrossingAsk_noTrades", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        vector<Trade> trades = ob.submitOrder(makeOrder(2, 105, 20, Side::ASK));
        check(trades.empty(), "non-crossing resting ask should produce no trades");
    });

    core("nonCrossingAsk_bestAsk", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        ob.submitOrder(makeOrder(2, 105, 20, Side::ASK));
        checkEqual(ob.getBestAsk(), 105, "best ask after resting ask");
    });

    core("nonCrossingAsk_bestBidUnaffected", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        ob.submitOrder(makeOrder(2, 105, 20, Side::ASK));
        checkEqual(ob.getBestBid(), 100, "best bid unaffected by resting ask");
    });

    core("nonCrossingAsk_totalVolume", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        ob.submitOrder(makeOrder(2, 105, 20, Side::ASK));
        checkEqual(ob.getTotalVolume(), 30, "total volume with bid+ask resting");
    });

    // ----- Aggregation at a shared price level -----

    core("sharedLevel_volumeAtPrice", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(10, 100, 5, Side::BUY));
        ob.submitOrder(makeOrder(11, 100, 7, Side::BUY));
        checkEqual(ob.getTotalVolumeAtPrice(100), 12, "aggregated volume at shared price level");
    });

    core("sharedLevel_ordersAtPrice", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(10, 100, 5, Side::BUY));
        ob.submitOrder(makeOrder(11, 100, 7, Side::BUY));
        vector<Order> expected = {
            makeOrder(10, 100, 5, Side::BUY),
            makeOrder(11, 100, 7, Side::BUY)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expected, "sharedLevel_ordersAtPrice");
    });

    // ----- Multiple price levels -----

    core("multiLevel_bestBidIsHighest", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 5, Side::BUY));
        ob.submitOrder(makeOrder(2, 105, 5, Side::BUY));
        ob.submitOrder(makeOrder(3, 102, 5, Side::BUY));
        checkEqual(ob.getBestBid(), 105, "best bid is highest of multiple levels");
    });

    core("multiLevel_bestAskIsLowest", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 110, 5, Side::ASK));
        ob.submitOrder(makeOrder(2, 108, 5, Side::ASK));
        ob.submitOrder(makeOrder(3, 115, 5, Side::ASK));
        checkEqual(ob.getBestAsk(), 108, "best ask is lowest of multiple levels");
    });

    core("multiLevel_totalVolume", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 90, 3, Side::BUY));
        ob.submitOrder(makeOrder(2, 95, 4, Side::BUY));
        ob.submitOrder(makeOrder(3, 110, 6, Side::ASK));
        ob.submitOrder(makeOrder(4, 115, 2, Side::ASK));
        checkEqual(ob.getTotalVolume(), 15, "total volume across multiple non-crossing levels");
    });

    // ----- Full match (single resting order, exact size) -----

    core("fullMatch_producesTrade", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(2, 100, 10, Side::BUY));
        vector<Trade> expected = {makeTrade(1, 2, 100, 10)};
        assertTradesMatch(trades, expected, "fullMatch_producesTrade");
    });

    core("fullMatch_levelEmptiedAfter", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 10, Side::BUY));
        checkEqual(ob.getTotalVolumeAtPrice(100), 0, "price level empty after full match");
        check(ob.getAllOrdersAtPrice(100).empty(), "no orders left at price after full match");
    });

    core("fullMatch_bestAskGone", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 10, Side::BUY));
        checkEqual(ob.getBestAsk(), -1, "best ask gone after full match consumes it");
    });

    // ----- Pro-rata matching -----

    core("proRata_exactSplitNoRemainder", [] {
        // Resting: id1=10, id2=10 (total=20). Incoming matches 10.
        // Each floor(10*10/20) = 5.0 -> 5 exactly. Remainder = 0.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 10, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(3, 100, 10, Side::BUY));

        vector<Trade> expectedTrades = {
            makeTrade(1, 3, 100, 5),
            makeTrade(2, 3, 100, 5)};
        assertTradesMatch(trades, expectedTrades, "proRata_exactSplitNoRemainder trades");

        vector<Order> expectedOrders = {
            makeOrder(1, 100, 5, Side::ASK),
            makeOrder(2, 100, 5, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_exactSplitNoRemainder resting");
        checkEqual(ob.getTotalVolumeAtPrice(100), 10, "proRata_exactSplitNoRemainder: remaining volume");
    });

    core("proRata_remainderGoesToBiggestShares", [] {
        // Resting (submitted in this order): id1=5, id2=15, id3=10 (total=30).
        // id1 is submitted FIRST but has the SMALLEST size -- this is
        // deliberately chosen to distinguish "biggest shares first" from a
        // pure FIFO-by-submission-order remainder rule.
        // Incoming matches 10 shares.
        //   id1: floor(10*5/30)  = 1
        //   id2: floor(10*15/30) = 5
        //   id3: floor(10*10/30) = 3
        //   sum = 9, remainder = 1 -> ranked by (shares desc, timestamp asc):
        //   id2(15) > id3(10) > id1(5). The remainder share goes to id2,
        //   the largest resting order, even though id1 was submitted earlier.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 5, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 15, Side::ASK));
        ob.submitOrder(makeOrder(3, 100, 10, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(4, 100, 10, Side::BUY));

        vector<Trade> expectedTrades = {
            makeTrade(1, 4, 100, 1),
            makeTrade(2, 4, 100, 6),
            makeTrade(3, 4, 100, 3)};
        assertTradesMatch(trades, expectedTrades, "proRata_remainderGoesToBiggestShares trades");

        vector<Order> expectedOrders = {
            makeOrder(1, 100, 4, Side::ASK),
            makeOrder(2, 100, 9, Side::ASK),
            makeOrder(3, 100, 7, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_remainderGoesToBiggestShares resting");
        checkEqual(ob.getTotalVolumeAtPrice(100), 20, "proRata_remainderGoesToBiggestShares: remaining volume");
    });

    core("proRata_remainderRankedBySizeThenTimestamp", [] {
        // Resting (submitted in this order): id1=5, id2=15, id3=15, id4=25 (total=60).
        // id2 and id3 are tied in size, and id4 -- despite being submitted
        // LAST -- has the largest size of all. This exercises both parts
        // of the remainder rule at once: rank by shares descending first,
        // then break ties by submission timestamp ascending.
        // Incoming matches 15 shares.
        //   id1: floor(15*5/60)  = 1
        //   id2: floor(15*15/60) = 3
        //   id3: floor(15*15/60) = 3
        //   id4: floor(15*25/60) = 6
        //   sum = 13, remainder = 2.
        //   Ranked: id4(25) > id2(15, submitted before id3) > id3(15) > id1(5).
        //   The 2 remainder shares go to id4 and then id2.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 5, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 15, Side::ASK));
        ob.submitOrder(makeOrder(3, 100, 15, Side::ASK));
        ob.submitOrder(makeOrder(4, 100, 25, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(5, 100, 15, Side::BUY));

        vector<Trade> expectedTrades = {
            makeTrade(1, 5, 100, 1),
            makeTrade(2, 5, 100, 4),
            makeTrade(3, 5, 100, 3),
            makeTrade(4, 5, 100, 7)};
        assertTradesMatch(trades, expectedTrades, "proRata_remainderRankedBySizeThenTimestamp trades");

        vector<Order> expectedOrders = {
            makeOrder(1, 100, 4, Side::ASK),
            makeOrder(2, 100, 11, Side::ASK),
            makeOrder(3, 100, 12, Side::ASK),
            makeOrder(4, 100, 18, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_remainderRankedBySizeThenTimestamp resting");
        checkEqual(ob.getTotalVolumeAtPrice(100), 45, "proRata_remainderRankedBySizeThenTimestamp: remaining volume");
    });

    core("proRata_remainderSpreadAcrossOrders", [] {
        // Resting (submission order): id1..id5 = 20 each (total=100).
        // All five orders are tied in size, so this isolates the
        // timestamp tie-break half of the rule: among equally-sized
        // orders, the remainder goes to the earliest submitted first.
        // Incoming matches 53 shares.
        //   each floor(53*20/100) = 10 -> sum = 50, remainder = 3.
        //   Ranked by (shares desc, timestamp asc): shares are tied, so
        //   this reduces to submission order -> id1, id2, id3 each get +1.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 20, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 20, Side::ASK));
        ob.submitOrder(makeOrder(3, 100, 20, Side::ASK));
        ob.submitOrder(makeOrder(4, 100, 20, Side::ASK));
        ob.submitOrder(makeOrder(5, 100, 20, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(6, 100, 53, Side::BUY));

        vector<Trade> expectedTrades = {
            makeTrade(1, 6, 100, 11),
            makeTrade(2, 6, 100, 11),
            makeTrade(3, 6, 100, 11),
            makeTrade(4, 6, 100, 10),
            makeTrade(5, 6, 100, 10)};
        assertTradesMatch(trades, expectedTrades, "proRata_remainderSpreadAcrossOrders trades");

        vector<Order> expectedOrders = {
            makeOrder(1, 100, 9, Side::ASK),
            makeOrder(2, 100, 9, Side::ASK),
            makeOrder(3, 100, 9, Side::ASK),
            makeOrder(4, 100, 10, Side::ASK),
            makeOrder(5, 100, 10, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_remainderSpreadAcrossOrders resting");
        checkEqual(ob.getTotalVolumeAtPrice(100), 47, "proRata_remainderSpreadAcrossOrders: remaining volume");
    });

    core("proRata_allFloorsZero", [] {
        // Exact reproduction of the spec example: "6 shares left, 12 orders,
        // give one each to the first 6."
        // 12 resting orders, each shares=100 (submitted id1..id12), total=1200.
        // All orders are tied in size, so ranking by (shares desc,
        // timestamp asc) reduces purely to submission order here.
        // Incoming matches only 6 shares.
        //   each floor(6*100/1200) = 0 -> sum = 0, remainder = 6.
        //   Ranked by (shares desc, timestamp asc): all tied on shares, so
        //   id1..id6 (earliest submitted) each get +1; id7..id12 get 0
        //   and therefore should NOT appear in the returned Trade vector.
        OrderBook ob;
        for (int i = 1; i <= 12; i++) {
            ob.submitOrder(makeOrder(i, 100, 100, Side::ASK));
        }
        vector<Trade> trades = ob.submitOrder(makeOrder(13, 100, 6, Side::BUY));

        vector<Trade> expectedTrades;
        for (int i = 1; i <= 6; i++) {
            expectedTrades.push_back(makeTrade(i, 13, 100, 1));
        }
        assertTradesMatch(trades, expectedTrades, "proRata_allFloorsZero trades");

        vector<Order> orders = ob.getAllOrdersAtPrice(100);
        for (long i = 1; i <= 6; i++) {
            checkOrderFields(findOrder(orders, i), i, 100, 99, Side::ASK,
                             "proRata_allFloorsZero-id" + to_string(i) + " (should have received +1 fill)");
        }
        for (long i = 7; i <= 12; i++) {
            checkOrderFields(findOrder(orders, i), i, 100, 100, Side::ASK,
                             "proRata_allFloorsZero-id" + to_string(i) + " (should be untouched)");
        }
        checkEqual(ob.getTotalVolumeAtPrice(100), 1194, "proRata_allFloorsZero: remaining volume (1200-6)");
    });

    // ----- Marketable order larger than resting liquidity at a level -----

    core("marketable_leftoverRestsAtLimitPrice", [] {
        // Single resting order, so no pro-rata ambiguity: the ask side is
        // wiped out and the leftover buy quantity rests as a new bid.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 5, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(2, 100, 12, Side::BUY));

        vector<Trade> expectedTrades = {makeTrade(1, 2, 100, 5)};
        assertTradesMatch(trades, expectedTrades, "marketable_leftoverRestsAtLimitPrice trades");

        checkEqual(ob.getTotalVolumeAtPrice(100), 7, "leftover buy quantity should rest at limit price");
        checkEqual(ob.getBestBid(), 100, "leftover buy becomes new best bid");
        checkEqual(ob.getBestAsk(), -1, "ask side fully consumed");

        vector<Order> expectedOrders = {makeOrder(2, 100, 7, Side::BUY)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "marketable_leftoverRestsAtLimitPrice resting");
    });

    // ----- Walking multiple price levels -----

    core("walkBook_twoLevels", [] {
        // Level 100: id1=5 (fully consumed first).
        // Level 101: id2=10, id3=10 (total=20), submitted in that order.
        // Incoming buy id4 price=101 shares=13 (marketable across both levels).
        //   Consumes level 100 fully (5), leaving 13-5=8 to match at level 101.
        //   id2: floor(8*10/20) = 4, id3: floor(8*10/20) = 4, remainder = 0.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 5, Side::ASK));
        ob.submitOrder(makeOrder(2, 101, 10, Side::ASK));
        ob.submitOrder(makeOrder(3, 101, 10, Side::ASK));
        vector<Trade> trades = ob.submitOrder(makeOrder(4, 101, 13, Side::BUY));

        vector<Trade> expectedTrades = {
            makeTrade(1, 4, 100, 5),
            makeTrade(2, 4, 101, 4),
            makeTrade(3, 4, 101, 4)};
        assertTradesMatch(trades, expectedTrades, "walkBook_twoLevels trades");

        checkEqual(ob.getTotalVolumeAtPrice(100), 0, "walkBook_twoLevels: level 100 fully consumed");
        checkEqual(ob.getTotalVolumeAtPrice(101), 12, "walkBook_twoLevels: level 101 partially consumed");
        checkEqual(ob.getBestAsk(), 101, "walkBook_twoLevels: best ask now at remaining level");

        vector<Order> expectedOrders = {
            makeOrder(2, 101, 6, Side::ASK),
            makeOrder(3, 101, 6, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(101), expectedOrders, "walkBook_twoLevels resting");
    });

    // ----- Sanity checks on an untouched book -----

    core("untouchedPrice_volumeIsZero", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.getTotalVolumeAtPrice(999), 0, "volume at unused price should be 0");
    });

    core("untouchedPrice_ordersEmpty", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        check(ob.getAllOrdersAtPrice(999).empty(), "orders at unused price should be empty");
    });

    // ================================================================
    // Cancel-order tests (only run when the cancel feature is enabled)
    // ================================================================

    cancelTest("cancel_onEmptyBookReturnsFalse", [] {
        OrderBook ob;
        checkEqual(ob.cancelOrder(999), false, "cancel on empty book should return false");
    });

    cancelTest("cancel_existingOrderReturnsTrue", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.cancelOrder(1), true, "cancel of existing order should return true");
    });

    cancelTest("cancel_removesOrderFromBook", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        ob.submitOrder(makeOrder(2, 105, 20, Side::ASK));
        ob.cancelOrder(1);
        checkEqual(ob.getTotalVolume(), 20, "total volume after cancel");
        checkEqual(ob.getTotalVolumeAtPrice(100), 0, "volume at cancelled price");
        checkEqual(ob.getBestBid(), -1, "best bid gone after cancel");
        check(ob.getAllOrdersAtPrice(100).empty(), "cancelled price level should have no orders");
    });

    cancelTest("cancel_twiceReturnsFalseSecondTime", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        ob.cancelOrder(1);
        checkEqual(ob.cancelOrder(1), false, "cancelling an already-cancelled order returns false");
    });

    cancelTest("cancel_neverUsedIdReturnsFalse", [] {
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::BUY));
        checkEqual(ob.cancelOrder(123456), false, "cancel of never-used id returns false");
    });

    cancelTest("cancel_afterProRataPartialFill", [] {
        // Cancel a resting order after it has been partially filled by a
        // pro-rata match, and confirm the book reflects the removal.
        // id1=10, id2=10 (total=20), incoming matches 10 -> exact split
        // (5/5). Cancel id1, leaving only id2's 5 remaining shares.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 100, 10, Side::ASK));
        ob.submitOrder(makeOrder(2, 100, 10, Side::ASK));
        ob.submitOrder(makeOrder(3, 100, 10, Side::BUY));

        checkEqual(ob.cancelOrder(1), true, "cancel of partially-filled resting order returns true");
        checkEqual(ob.getTotalVolumeAtPrice(100), 5, "only id2's remaining 5 shares left");

        vector<Order> expectedOrders = {makeOrder(2, 100, 5, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "cancel_afterProRataPartialFill resting");
    });

    cancelTest("endToEnd_restsMatchesCancelsAndConsistency", [] {
        // Combines resting orders, a pro-rata crossing match (floor + FIFO
        // remainder), a cancel, and final consistency checks.
        OrderBook ob;
        ob.submitOrder(makeOrder(1, 95, 10, Side::BUY));
        ob.submitOrder(makeOrder(2, 90, 5, Side::BUY));
        // Three resting asks at 105, submitted in order id3 (7), id4 (7), id5 (6).
        // Total at 105 = 20.
        ob.submitOrder(makeOrder(3, 105, 7, Side::ASK));
        ob.submitOrder(makeOrder(4, 105, 7, Side::ASK));
        ob.submitOrder(makeOrder(5, 105, 6, Side::ASK));

        checkEqual(ob.getBestBid(), 95, "e2e: best bid before crossing");
        checkEqual(ob.getBestAsk(), 105, "e2e: best ask before crossing");

        // Cancel one resting bid before the crossing match.
        checkEqual(ob.cancelOrder(2), true, "e2e: cancel resting bid returns true");
        checkEqual(ob.getTotalVolumeAtPrice(90), 0, "e2e: cancelled level empty");

        // Incoming buy matches 11 shares at price 105 against id3/id4/id5.
        //   id3: floor(11*7/20) = 3, id4: floor(11*7/20) = 3, id5: floor(11*6/20) = 3
        //   sum = 9, remainder = 2 -> ranked by (shares desc, timestamp asc):
        //   id3(7) and id4(7) outrank id5(6); between the tied id3/id4,
        //   id3 was submitted first. So id3 and id4 each get +1.
        // Final fills: id3=4, id4=4, id5=3 (sum=11).
        vector<Trade> trades = ob.submitOrder(makeOrder(6, 105, 11, Side::BUY));
        vector<Trade> expectedTrades = {
            makeTrade(3, 6, 105, 4),
            makeTrade(4, 6, 105, 4),
            makeTrade(5, 6, 105, 3)};
        assertTradesMatch(trades, expectedTrades, "e2e: crossing trades");

        checkEqual(ob.getTotalVolumeAtPrice(105), 9, "e2e: level 105 remaining volume after pro-rata match");
        vector<Order> expectedResting = {
            makeOrder(3, 105, 3, Side::ASK),
            makeOrder(4, 105, 3, Side::ASK),
            makeOrder(5, 105, 3, Side::ASK)};
        assertOrdersMatch(ob.getAllOrdersAtPrice(105), expectedResting, "e2e: resting remainders");
        checkEqual(ob.getBestAsk(), 105, "e2e: best ask still 105 (level not fully consumed)");

        // Original untouched bid should still be there.
        checkEqual(ob.getTotalVolumeAtPrice(95), 10, "e2e: untouched bid level unaffected");
        checkEqual(ob.getBestBid(), 95, "e2e: best bid still 95");

        // Total volume sanity check: 10 (bid@95) + 9 (remaining ask@105) = 19
        checkEqual(ob.getTotalVolume(), 19, "e2e: final total volume");
    });
}

// ---------------------------------------------------------------------
// Test runner
// ---------------------------------------------------------------------

static void printUsageAndExit() {
    cerr << "Usage: ./test <0|1>" << endl;
    cerr << "  0 -> run the core suite only (no cancelOrder tests)" << endl;
    cerr << "  1 -> run the full suite (core + cancelOrder tests)" << endl;
    exit(2);
}

int main(int argc, char** argv) {
    signal(SIGSEGV, crashHandler);
    signal(SIGABRT, crashHandler);

    if (argc != 2 || (string(argv[1]) != "0" && string(argv[1]) != "1")) {
        printUsageAndExit();
    }
    bool includeCancelTests = (string(argv[1]) == "1");

    registerTests();

    vector<TestCase> toRun;
    for (const auto& tc : registry()) {
        if (!tc.requiresCancel || includeCancelTests) {
            toRun.push_back(tc);
        }
    }
    g_total = static_cast<int>(toRun.size());

    cout << "Running in " << (includeCancelTests ? "FULL (cancel enabled)" : "CORE (no cancel)")
         << " mode: " << g_total << " tests." << endl << endl;

    for (int i = 0; i < g_total; i++) {
        const TestCase& tc = toRun[i];
        g_currentTestName = tc.name;
        cout << "[" << (i + 1) << "/" << g_total << "] " << tc.name << " ... " << flush;

        try {
            tc.body();
            cout << "PASSED" << endl;
            g_passedCount++;
        } catch (TestFailure& f) {
            cout << "FAILED" << endl;
            cerr << "  Reason: " << f.message << endl;
            break;
        } catch (exception& e) {
            cout << "FAILED (uncaught exception)" << endl;
            cerr << "  Reason: " << e.what() << endl;
            break;
        } catch (...) {
            cout << "FAILED (unknown exception)" << endl;
            break;
        }
    }

    cout << "\n=====================================" << endl;
    cout << "RESULTS: " << g_passedCount << "/" << g_total << " test cases passed." << endl;
    cout << "=====================================" << endl;

    return (g_passedCount == g_total) ? 0 : 1;
}
