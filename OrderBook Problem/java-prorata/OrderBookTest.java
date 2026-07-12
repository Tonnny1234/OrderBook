// OrderBookTest.java
// Test harness for OrderBook (pro-rata limit order book, FIFO remainder allocation).
//
// USAGE:
//   java OrderBookTest 0     -> run the CORE suite only (no cancelOrder tests)
//   java OrderBookTest 1     -> run the FULL suite (core + cancelOrder tests)
//
// Behavior:
//   - Each test is its own method, using a fresh OrderBook instance.
//   - Every test is tagged with whether it depends on cancelOrder. Mode 0 runs
//     only the untagged ("core") tests; mode 1 runs all of them.
//   - Tests run in declared order. Progress is printed before each test runs,
//     so if a test crashes the JVM outright, stdout already shows which test
//     was in flight.
//   - A shutdown hook prints the last known test name if the JVM exits before
//     the harness finishes normally (e.g. a fatal native crash). Most Java
//     errors (NullPointerException, StackOverflowError, etc.) don't kill the
//     JVM the way a C++ segfault does -- they're plain Throwables -- so the
//     main try/catch below already handles those directly.
//   - On the first LOGICAL failure (a check() that fails), we stop
//     immediately and report how many passed.
//
// Pro-rata allocation rule (as specified by the assignment):
//   - Each resting order at the level gets floor(matchQty * theirShares / totalSharesAtLevel).
//   - This floor step under-allocates by some remainder R = matchQty - sum(floors).
//   - R is distributed 1 share at a time, in FIFO (submission) order, to the
//     first R orders at that level. E.g. 6 shares left over 12 orders means
//     the first 6 orders (by submission order) each get exactly 1 extra
//     share, and the remaining 6 orders get 0 extra.
// Tests below construct exact numeric scenarios for this rule so results are
// unambiguous (no ties in the floor step itself -- only the well-specified
// FIFO remainder step is exercised).
//
// Assumptions made about OrderBook semantics beyond the pro-rata rule itself:
//   1. submitOrder(order) returns the list of Trades produced by that single
//      call. If the order rests untouched (does not cross the opposite
//      side), it returns an empty list.
//   2. When a marketable order only partially fills against available
//      resting liquidity, the remainder rests in the book at its own limit
//      price (standard limit-order-book behavior).
//   3. getBestBid()/getBestAsk() return -1 when that side of the book is empty.
//   4. cancelOrder(id) returns true if an order with that id was found and
//      removed, false otherwise (including calling it twice on the same id).
//   5. Side.BUY orders are bids, Side.ASK orders are asks/offers.
//   6. getTotalVolumeAtPrice(price) / getAllOrdersAtPrice(price) count BOTH
//      sides resting at that exact price (a price level is just "resting
//      quantity at that price", regardless of side).
//   7. A Trade is only emitted for resting orders that actually receive a
//      nonzero fill; resting orders whose pro-rata floor share is 0 and who
//      don't receive part of the FIFO remainder do not appear in the
//      returned Trade list at all.

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OrderBookTest {

    // ---------------------------------------------------------------
    // Test-failure plumbing
    // ---------------------------------------------------------------

    static class TestFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TestFailure(String message) {
            super(message);
        }
    }

    private static void check(boolean condition, String msg) {
        if (!condition) {
            throw new TestFailure(msg);
        }
    }

    private static void checkEqual(long actual, long expected, String ctx) {
        if (actual != expected) {
            throw new TestFailure(ctx + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void checkEqual(boolean actual, boolean expected, String ctx) {
        if (actual != expected) {
            throw new TestFailure(ctx + " (expected " + expected + ", got " + actual + ")");
        }
    }

    // ---------------------------------------------------------------
    // Construction helpers
    // ---------------------------------------------------------------

    private static Order makeOrder(int id, int price, int shares, Side side) {
        Order o = new Order();
        o.orderId = id;
        o.price = price;
        o.shares = shares;
        o.side = side;
        return o;
    }

    private static Trade makeTrade(int restingId, int aggressiveId, int price, int qty) {
        return new Trade(restingId, aggressiveId, price, qty);
    }

    // ---------------------------------------------------------------
    // Unordered ("set-like") comparison helpers.
    //
    // getAllOrders()/getAllOrdersAtPrice() and the Trade list returned by
    // submitOrder() are documented as NOT having a guaranteed order. We
    // still want a one-to-one, fully-specified comparison against the
    // expected collection, so both sides get sorted onto the same key
    // before comparing element-by-element. orderId is unique, so it's a
    // safe sort key for Orders. Within a single submitOrder() call a
    // resting order can only appear once in the returned Trade list, so
    // restingOrderId is a safe sort key for Trades.
    // ---------------------------------------------------------------

    private static Order findOrder(List<Order> orders, long id) {
        for (Order o : orders) {
            if (o.orderId == id) return o;
        }
        return null;
    }

    private static void checkOrderFields(Order o, long expId, long expPrice,
                                          long expShares, Side expSide, String ctx) {
        check(o != null, ctx + ": order id " + expId + " not found");
        check(o.orderId == expId, ctx + ": orderId mismatch");
        checkEqual(o.price, expPrice, ctx + ": price mismatch for order " + expId);
        checkEqual(o.shares, expShares, ctx + ": shares mismatch for order " + expId);
        check(o.side == expSide, ctx + ": side mismatch for order " + expId);
    }

    /** Asserts actual and expected contain the same orders, ignoring list order. */
    private static void assertOrdersMatch(List<Order> actual, List<Order> expected, String ctx) {
        checkEqual(actual.size(), expected.size(), ctx + ": order count mismatch");
        List<Order> sortedExpected = new ArrayList<>(expected);
        sortedExpected.sort(Comparator.comparingLong(o -> o.orderId));
        for (Order e : sortedExpected) {
            Order a = findOrder(actual, e.orderId);
            checkOrderFields(a, e.orderId, e.price, e.shares, e.side, ctx);
        }
    }

    /** Asserts actual and expected contain the same trades, ignoring list order. */
    private static void assertTradesMatch(List<Trade> actual, List<Trade> expected, String ctx) {
        checkEqual(actual.size(), expected.size(), ctx + ": trade count mismatch");
        List<Trade> sortedActual = new ArrayList<>(actual);
        List<Trade> sortedExpected = new ArrayList<>(expected);
        Comparator<Trade> byResting = Comparator.comparingInt(t -> t.restingOrderId);
        sortedActual.sort(byResting);
        sortedExpected.sort(byResting);
        for (int i = 0; i < sortedExpected.size(); i++) {
            Trade e = sortedExpected.get(i);
            Trade a = sortedActual.get(i);
            String tctx = ctx + ": trade vs resting order " + e.restingOrderId;
            checkEqual(a.restingOrderId, e.restingOrderId, tctx + " (restingOrderId)");
            checkEqual(a.aggressiveOrderId, e.aggressiveOrderId, tctx + " (aggressiveOrderId)");
            checkEqual(a.tradingPrice, e.tradingPrice, tctx + " (tradingPrice)");
            checkEqual(a.quantity, e.quantity, tctx + " (quantity)");
        }
    }

    // ---------------------------------------------------------------
    // Test-case registration: each test is tagged with whether it needs
    // cancelOrder support.
    // ---------------------------------------------------------------

    private interface TestBody {
        void run();
    }

    private static class TestCase {
        final String name;
        final boolean requiresCancel;
        final TestBody body;

        TestCase(String name, boolean requiresCancel, TestBody body) {
            this.name = name;
            this.requiresCancel = requiresCancel;
            this.body = body;
        }
    }

    private static final List<TestCase> REGISTRY = new ArrayList<>();

    private static void core(String name, TestBody body) {
        REGISTRY.add(new TestCase(name, false, body));
    }

    private static void cancelTest(String name, TestBody body) {
        REGISTRY.add(new TestCase(name, true, body));
    }

    // ---------------------------------------------------------------
    // Test registration
    // ---------------------------------------------------------------

    static {

        // ----- Empty book -----

        core("emptyBook_bestBid", () -> {
            OrderBook ob = new OrderBook();
            checkEqual(ob.getBestBid(), -1, "empty book best bid");
        });

        core("emptyBook_bestAsk", () -> {
            OrderBook ob = new OrderBook();
            checkEqual(ob.getBestAsk(), -1, "empty book best ask");
        });

        core("emptyBook_totalVolume", () -> {
            OrderBook ob = new OrderBook();
            checkEqual(ob.getTotalVolume(), 0, "empty book total volume");
        });

        core("emptyBook_getAllOrders", () -> {
            OrderBook ob = new OrderBook();
            check(ob.getAllOrders().isEmpty(), "empty book getAllOrders should be empty");
        });

        core("emptyBook_volumeAtPrice", () -> {
            OrderBook ob = new OrderBook();
            checkEqual(ob.getTotalVolumeAtPrice(100), 0, "empty book volume at price 100");
        });

        core("emptyBook_ordersAtPrice", () -> {
            OrderBook ob = new OrderBook();
            check(ob.getAllOrdersAtPrice(100).isEmpty(), "empty book orders at price 100 should be empty");
        });

        // ----- Single resting order -----

        core("singleRestingBuy_noTrades", () -> {
            OrderBook ob = new OrderBook();
            List<Trade> trades = ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            check(trades.isEmpty(), "resting buy order should produce no trades");
        });

        core("singleRestingBuy_bestBid", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.getBestBid(), 100, "best bid after single resting buy");
        });

        core("singleRestingBuy_bestAskUntouched", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.getBestAsk(), -1, "best ask should remain empty");
        });

        core("singleRestingBuy_totalVolume", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.getTotalVolume(), 10, "total volume after single resting order");
        });

        core("singleRestingBuy_volumeAtPrice", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.getTotalVolumeAtPrice(100), 10, "volume at price after resting order");
        });

        core("singleRestingBuy_ordersAtPrice", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            List<Order> expected = List.of(makeOrder(1, 100, 10, Side.BUY));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expected, "singleRestingBuy_ordersAtPrice");
        });

        core("singleRestingBuy_getAllOrders", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            List<Order> expected = List.of(makeOrder(1, 100, 10, Side.BUY));
            assertOrdersMatch(ob.getAllOrders(), expected, "singleRestingBuy_getAllOrders");
        });

        // ----- Two-sided, non-crossing book -----

        core("nonCrossingAsk_noTrades", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            List<Trade> trades = ob.submitOrder(makeOrder(2, 105, 20, Side.ASK));
            check(trades.isEmpty(), "non-crossing resting ask should produce no trades");
        });

        core("nonCrossingAsk_bestAsk", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            ob.submitOrder(makeOrder(2, 105, 20, Side.ASK));
            checkEqual(ob.getBestAsk(), 105, "best ask after resting ask");
        });

        core("nonCrossingAsk_bestBidUnaffected", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            ob.submitOrder(makeOrder(2, 105, 20, Side.ASK));
            checkEqual(ob.getBestBid(), 100, "best bid unaffected by resting ask");
        });

        core("nonCrossingAsk_totalVolume", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            ob.submitOrder(makeOrder(2, 105, 20, Side.ASK));
            checkEqual(ob.getTotalVolume(), 30, "total volume with bid+ask resting");
        });

        // ----- Aggregation at a shared price level -----

        core("sharedLevel_volumeAtPrice", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(10, 100, 5, Side.BUY));
            ob.submitOrder(makeOrder(11, 100, 7, Side.BUY));
            checkEqual(ob.getTotalVolumeAtPrice(100), 12, "aggregated volume at shared price level");
        });

        core("sharedLevel_ordersAtPrice", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(10, 100, 5, Side.BUY));
            ob.submitOrder(makeOrder(11, 100, 7, Side.BUY));
            List<Order> expected = List.of(
                    makeOrder(10, 100, 5, Side.BUY),
                    makeOrder(11, 100, 7, Side.BUY));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expected, "sharedLevel_ordersAtPrice");
        });

        // ----- Multiple price levels -----

        core("multiLevel_bestBidIsHighest", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 5, Side.BUY));
            ob.submitOrder(makeOrder(2, 105, 5, Side.BUY));
            ob.submitOrder(makeOrder(3, 102, 5, Side.BUY));
            checkEqual(ob.getBestBid(), 105, "best bid is highest of multiple levels");
        });

        core("multiLevel_bestAskIsLowest", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 110, 5, Side.ASK));
            ob.submitOrder(makeOrder(2, 108, 5, Side.ASK));
            ob.submitOrder(makeOrder(3, 115, 5, Side.ASK));
            checkEqual(ob.getBestAsk(), 108, "best ask is lowest of multiple levels");
        });

        core("multiLevel_totalVolume", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 90, 3, Side.BUY));
            ob.submitOrder(makeOrder(2, 95, 4, Side.BUY));
            ob.submitOrder(makeOrder(3, 110, 6, Side.ASK));
            ob.submitOrder(makeOrder(4, 115, 2, Side.ASK));
            checkEqual(ob.getTotalVolume(), 15, "total volume across multiple non-crossing levels");
        });

        // ----- Full match (single resting order, exact size) -----

        core("fullMatch_producesTrade", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.ASK));
            List<Trade> trades = ob.submitOrder(makeOrder(2, 100, 10, Side.BUY));
            List<Trade> expected = List.of(makeTrade(1, 2, 100, 10));
            assertTradesMatch(trades, expected, "fullMatch_producesTrade");
        });

        core("fullMatch_levelEmptiedAfter", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.ASK));
            ob.submitOrder(makeOrder(2, 100, 10, Side.BUY));
            checkEqual(ob.getTotalVolumeAtPrice(100), 0, "price level empty after full match");
            check(ob.getAllOrdersAtPrice(100).isEmpty(), "no orders left at price after full match");
        });

        core("fullMatch_bestAskGone", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.ASK));
            ob.submitOrder(makeOrder(2, 100, 10, Side.BUY));
            checkEqual(ob.getBestAsk(), -1, "best ask gone after full match consumes it");
        });

        // ----- Pro-rata matching -----

        core("proRata_exactSplitNoRemainder", () -> {
            // Resting: id1=10, id2=10 (total=20). Incoming matches 10.
            // Each floor(10*10/20) = 5.0 -> 5 exactly. Remainder = 0.
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.ASK));
            ob.submitOrder(makeOrder(2, 100, 10, Side.ASK));
            List<Trade> trades = ob.submitOrder(makeOrder(3, 100, 10, Side.BUY));

            List<Trade> expectedTrades = List.of(
                    makeTrade(1, 3, 100, 5),
                    makeTrade(2, 3, 100, 5));
            assertTradesMatch(trades, expectedTrades, "proRata_exactSplitNoRemainder trades");

            List<Order> expectedOrders = List.of(
                    makeOrder(1, 100, 5, Side.ASK),
                    makeOrder(2, 100, 5, Side.ASK));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_exactSplitNoRemainder resting");
            checkEqual(ob.getTotalVolumeAtPrice(100), 10, "proRata_exactSplitNoRemainder: remaining volume");
        });

        core("proRata_fifoRemainderToFirstOrder", () -> {
            // Resting (submitted in this order): id1=10, id2=5, id3=5 (total=20).
            // Incoming matches 10 shares.
            //   id1: floor(10*10/20) = 5
            //   id2: floor(10*5/20)  = 2
            //   id3: floor(10*5/20)  = 2
            //   sum = 9, remainder = 1 -> goes to id1 (first by submission order).
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.ASK));
            ob.submitOrder(makeOrder(2, 100, 5, Side.ASK));
            ob.submitOrder(makeOrder(3, 100, 5, Side.ASK));
            List<Trade> trades = ob.submitOrder(makeOrder(4, 100, 10, Side.BUY));

            List<Trade> expectedTrades = List.of(
                    makeTrade(1, 4, 100, 6),
                    makeTrade(2, 4, 100, 2),
                    makeTrade(3, 4, 100, 2));
            assertTradesMatch(trades, expectedTrades, "proRata_fifoRemainderToFirstOrder trades");

            List<Order> expectedOrders = List.of(
                    makeOrder(1, 100, 4, Side.ASK),
                    makeOrder(2, 100, 3, Side.ASK),
                    makeOrder(3, 100, 3, Side.ASK));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_fifoRemainderToFirstOrder resting");
            checkEqual(ob.getTotalVolumeAtPrice(100), 10, "proRata_fifoRemainderToFirstOrder: remaining volume");
        });

        core("proRata_remainderSpreadAcrossOrders", () -> {
            // Resting (submission order): id1..id5 = 20 each (total=100).
            // Incoming matches 53 shares.
            //   each floor(53*20/100) = 10 -> sum = 50, remainder = 3.
            //   First 3 by submission order (id1, id2, id3) each get +1.
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 20, Side.ASK));
            ob.submitOrder(makeOrder(2, 100, 20, Side.ASK));
            ob.submitOrder(makeOrder(3, 100, 20, Side.ASK));
            ob.submitOrder(makeOrder(4, 100, 20, Side.ASK));
            ob.submitOrder(makeOrder(5, 100, 20, Side.ASK));
            List<Trade> trades = ob.submitOrder(makeOrder(6, 100, 53, Side.BUY));

            List<Trade> expectedTrades = List.of(
                    makeTrade(1, 6, 100, 11),
                    makeTrade(2, 6, 100, 11),
                    makeTrade(3, 6, 100, 11),
                    makeTrade(4, 6, 100, 10),
                    makeTrade(5, 6, 100, 10));
            assertTradesMatch(trades, expectedTrades, "proRata_remainderSpreadAcrossOrders trades");

            List<Order> expectedOrders = List.of(
                    makeOrder(1, 100, 9, Side.ASK),
                    makeOrder(2, 100, 9, Side.ASK),
                    makeOrder(3, 100, 9, Side.ASK),
                    makeOrder(4, 100, 10, Side.ASK),
                    makeOrder(5, 100, 10, Side.ASK));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "proRata_remainderSpreadAcrossOrders resting");
            checkEqual(ob.getTotalVolumeAtPrice(100), 47, "proRata_remainderSpreadAcrossOrders: remaining volume");
        });

        core("proRata_allFloorsZero", () -> {
            // Exact reproduction of the spec example: "6 shares left, 12 orders,
            // give one each to the first 6."
            // 12 resting orders, each shares=100 (submitted id1..id12), total=1200.
            // Incoming matches only 6 shares.
            //   each floor(6*100/1200) = 0 -> sum = 0, remainder = 6.
            //   First 6 by submission order (id1..id6) each get +1; id7..id12 get 0
            //   and therefore should NOT appear in the returned Trade list.
            OrderBook ob = new OrderBook();
            for (int i = 1; i <= 12; i++) {
                ob.submitOrder(makeOrder(i, 100, 100, Side.ASK));
            }
            List<Trade> trades = ob.submitOrder(makeOrder(13, 100, 6, Side.BUY));

            List<Trade> expectedTrades = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                expectedTrades.add(makeTrade(i, 13, 100, 1));
            }
            assertTradesMatch(trades, expectedTrades, "proRata_allFloorsZero trades");

            List<Order> orders = ob.getAllOrdersAtPrice(100);
            for (long i = 1; i <= 6; i++) {
                checkOrderFields(findOrder(orders, i), i, 100, 99, Side.ASK,
                        "proRata_allFloorsZero-id" + i + " (should have received +1 fill)");
            }
            for (long i = 7; i <= 12; i++) {
                checkOrderFields(findOrder(orders, i), i, 100, 100, Side.ASK,
                        "proRata_allFloorsZero-id" + i + " (should be untouched)");
            }
            checkEqual(ob.getTotalVolumeAtPrice(100), 1194, "proRata_allFloorsZero: remaining volume (1200-6)");
        });

        // ----- Marketable order larger than resting liquidity at a level -----

        core("marketable_leftoverRestsAtLimitPrice", () -> {
            // Single resting order, so no pro-rata ambiguity: the ask side is
            // wiped out and the leftover buy quantity rests as a new bid.
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 5, Side.ASK));
            List<Trade> trades = ob.submitOrder(makeOrder(2, 100, 12, Side.BUY));

            List<Trade> expectedTrades = List.of(makeTrade(1, 2, 100, 5));
            assertTradesMatch(trades, expectedTrades, "marketable_leftoverRestsAtLimitPrice trades");

            checkEqual(ob.getTotalVolumeAtPrice(100), 7, "leftover buy quantity should rest at limit price");
            checkEqual(ob.getBestBid(), 100, "leftover buy becomes new best bid");
            checkEqual(ob.getBestAsk(), -1, "ask side fully consumed");

            List<Order> expectedOrders = List.of(makeOrder(2, 100, 7, Side.BUY));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "marketable_leftoverRestsAtLimitPrice resting");
        });

        // ----- Walking multiple price levels -----

        core("walkBook_twoLevels", () -> {
            // Level 100: id1=5 (fully consumed first).
            // Level 101: id2=10, id3=10 (total=20), submitted in that order.
            // Incoming buy id4 price=101 shares=13 (marketable across both levels).
            //   Consumes level 100 fully (5), leaving 13-5=8 to match at level 101.
            //   id2: floor(8*10/20) = 4, id3: floor(8*10/20) = 4, remainder = 0.
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 5, Side.ASK));
            ob.submitOrder(makeOrder(2, 101, 10, Side.ASK));
            ob.submitOrder(makeOrder(3, 101, 10, Side.ASK));
            List<Trade> trades = ob.submitOrder(makeOrder(4, 101, 13, Side.BUY));

            List<Trade> expectedTrades = List.of(
                    makeTrade(1, 4, 100, 5),
                    makeTrade(2, 4, 101, 4),
                    makeTrade(3, 4, 101, 4));
            assertTradesMatch(trades, expectedTrades, "walkBook_twoLevels trades");

            checkEqual(ob.getTotalVolumeAtPrice(100), 0, "walkBook_twoLevels: level 100 fully consumed");
            checkEqual(ob.getTotalVolumeAtPrice(101), 12, "walkBook_twoLevels: level 101 partially consumed");
            checkEqual(ob.getBestAsk(), 101, "walkBook_twoLevels: best ask now at remaining level");

            List<Order> expectedOrders = List.of(
                    makeOrder(2, 101, 6, Side.ASK),
                    makeOrder(3, 101, 6, Side.ASK));
            assertOrdersMatch(ob.getAllOrdersAtPrice(101), expectedOrders, "walkBook_twoLevels resting");
        });

        // ----- Sanity checks on an untouched book -----

        core("untouchedPrice_volumeIsZero", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.getTotalVolumeAtPrice(999), 0, "volume at unused price should be 0");
        });

        core("untouchedPrice_ordersEmpty", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            check(ob.getAllOrdersAtPrice(999).isEmpty(), "orders at unused price should be empty");
        });

        // ================================================================
        // Cancel-order tests (only run when the cancel feature is enabled)
        // ================================================================

        cancelTest("cancel_onEmptyBookReturnsFalse", () -> {
            OrderBook ob = new OrderBook();
            checkEqual(ob.cancelOrder(999), false, "cancel on empty book should return false");
        });

        cancelTest("cancel_existingOrderReturnsTrue", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.cancelOrder(1), true, "cancel of existing order should return true");
        });

        cancelTest("cancel_removesOrderFromBook", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            ob.submitOrder(makeOrder(2, 105, 20, Side.ASK));
            ob.cancelOrder(1);
            checkEqual(ob.getTotalVolume(), 20, "total volume after cancel");
            checkEqual(ob.getTotalVolumeAtPrice(100), 0, "volume at cancelled price");
            checkEqual(ob.getBestBid(), -1, "best bid gone after cancel");
            check(ob.getAllOrdersAtPrice(100).isEmpty(), "cancelled price level should have no orders");
        });

        cancelTest("cancel_twiceReturnsFalseSecondTime", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            ob.cancelOrder(1);
            checkEqual(ob.cancelOrder(1), false, "cancelling an already-cancelled order returns false");
        });

        cancelTest("cancel_neverUsedIdReturnsFalse", () -> {
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.BUY));
            checkEqual(ob.cancelOrder(123456), false, "cancel of never-used id returns false");
        });

        cancelTest("cancel_afterProRataPartialFill", () -> {
            // Cancel a resting order after it has been partially filled by a
            // pro-rata match, and confirm the book reflects the removal.
            // id1=10, id2=10 (total=20), incoming matches 10 -> exact split
            // (5/5). Cancel id1, leaving only id2's 5 remaining shares.
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 100, 10, Side.ASK));
            ob.submitOrder(makeOrder(2, 100, 10, Side.ASK));
            ob.submitOrder(makeOrder(3, 100, 10, Side.BUY));

            checkEqual(ob.cancelOrder(1), true, "cancel of partially-filled resting order returns true");
            checkEqual(ob.getTotalVolumeAtPrice(100), 5, "only id2's remaining 5 shares left");

            List<Order> expectedOrders = List.of(makeOrder(2, 100, 5, Side.ASK));
            assertOrdersMatch(ob.getAllOrdersAtPrice(100), expectedOrders, "cancel_afterProRataPartialFill resting");
        });

        cancelTest("endToEnd_restsMatchesCancelsAndConsistency", () -> {
            // Combines resting orders, a pro-rata crossing match (floor + FIFO
            // remainder), a cancel, and final consistency checks.
            OrderBook ob = new OrderBook();
            ob.submitOrder(makeOrder(1, 95, 10, Side.BUY));
            ob.submitOrder(makeOrder(2, 90, 5, Side.BUY));
            // Three resting asks at 105, submitted in order id3 (7), id4 (7), id5 (6).
            // Total at 105 = 20.
            ob.submitOrder(makeOrder(3, 105, 7, Side.ASK));
            ob.submitOrder(makeOrder(4, 105, 7, Side.ASK));
            ob.submitOrder(makeOrder(5, 105, 6, Side.ASK));

            checkEqual(ob.getBestBid(), 95, "e2e: best bid before crossing");
            checkEqual(ob.getBestAsk(), 105, "e2e: best ask before crossing");

            // Cancel one resting bid before the crossing match.
            checkEqual(ob.cancelOrder(2), true, "e2e: cancel resting bid returns true");
            checkEqual(ob.getTotalVolumeAtPrice(90), 0, "e2e: cancelled level empty");

            // Incoming buy matches 11 shares at price 105 against id3/id4/id5.
            //   id3: floor(11*7/20) = 3, id4: floor(11*7/20) = 3, id5: floor(11*6/20) = 3
            //   sum = 9, remainder = 2 -> first 2 by submission order (id3, id4) get +1.
            // Final fills: id3=4, id4=4, id5=3 (sum=11).
            List<Trade> trades = ob.submitOrder(makeOrder(6, 105, 11, Side.BUY));
            List<Trade> expectedTrades = List.of(
                    makeTrade(3, 6, 105, 4),
                    makeTrade(4, 6, 105, 4),
                    makeTrade(5, 6, 105, 3));
            assertTradesMatch(trades, expectedTrades, "e2e: crossing trades");

            checkEqual(ob.getTotalVolumeAtPrice(105), 9, "e2e: level 105 remaining volume after pro-rata match");
            List<Order> expectedResting = List.of(
                    makeOrder(3, 105, 3, Side.ASK),
                    makeOrder(4, 105, 3, Side.ASK),
                    makeOrder(5, 105, 3, Side.ASK));
            assertOrdersMatch(ob.getAllOrdersAtPrice(105), expectedResting, "e2e: resting remainders");
            checkEqual(ob.getBestAsk(), 105, "e2e: best ask still 105 (level not fully consumed)");

            // Original untouched bid should still be there.
            checkEqual(ob.getTotalVolumeAtPrice(95), 10, "e2e: untouched bid level unaffected");
            checkEqual(ob.getBestBid(), 95, "e2e: best bid still 95");

            // Total volume sanity check: 10 (bid@95) + 9 (remaining ask@105) = 19
            checkEqual(ob.getTotalVolume(), 19, "e2e: final total volume");
        });
    }

    // ---------------------------------------------------------------
    // Test runner
    // ---------------------------------------------------------------

    private static volatile String currentTestName = "(not started)";
    private static volatile int passedCount = 0;
    private static volatile boolean finishedNormally = false;

    private static void printUsageAndExit() {
        System.err.println("Usage: java OrderBookTest <0|1>");
        System.err.println("  0 -> run the core suite only (no cancelOrder tests)");
        System.err.println("  1 -> run the full suite (core + cancelOrder tests)");
        System.exit(2);
    }

    public static void main(String[] args) {
        if (args.length != 1 || (!args[0].equals("0") && !args[0].equals("1"))) {
            printUsageAndExit();
            return;
        }
        boolean includeCancelTests = args[0].equals("1");

        List<TestCase> toRun = new ArrayList<>();
        for (TestCase tc : REGISTRY) {
            if (!tc.requiresCancel || includeCancelTests) {
                toRun.add(tc);
            }
        }
        final int total = toRun.size();

        // Safety net: if the JVM exits abnormally (e.g. a fatal native crash)
        // before we finish normally, print what we know.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!finishedNormally) {
                System.err.println();
                System.err.println("*** JVM EXITED UNEXPECTEDLY during test \"" + currentTestName + "\" ***");
                System.err.println(passedCount + "/" + total + " test cases passed before the exit.");
            }
        }));

        System.out.println("Running in " + (includeCancelTests ? "FULL (cancel enabled)" : "CORE (no cancel)") + " mode: " + total + " tests.");
        System.out.println();

        for (int i = 0; i < total; i++) {
            TestCase tc = toRun.get(i);
            currentTestName = tc.name;
            System.out.print("[" + (i + 1) + "/" + total + "] " + tc.name + " ... ");
            System.out.flush();

            try {
                tc.body.run();
                System.out.println("PASSED");
                passedCount++;
            } catch (TestFailure f) {
                System.out.println("FAILED");
                System.err.println("  Reason: " + f.getMessage());
                break;
            } catch (Throwable t) {
                // Catches StackOverflowError, OutOfMemoryError, NullPointerException,
                // ArrayIndexOutOfBoundsException, etc. -- anything the implementation
                // might throw that isn't one of our own TestFailures.
                System.out.println("FAILED (uncaught " + t.getClass().getSimpleName() + ")");
                System.err.println("  Reason: " + t.getMessage());
                break;
            }
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("RESULTS: " + passedCount + "/" + total + " test cases passed.");
        System.out.println("=====================================");

        finishedNormally = true;
        System.exit(passedCount == total ? 0 : 1);
    }
}