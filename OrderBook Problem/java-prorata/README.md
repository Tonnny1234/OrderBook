# OrderBook

Implement the `OrderBook` class: a **pro-rata limit order book with FIFO
remainder allocation**.

Note: Remainder allocation determined first by (shares desc, timestamp asc). You will give a share to the R orders
determined by this ordering.

## Files

| File | Description |
|---|---|
| `Side.java` | `enum Side { BUY, ASK }` |
| `Order.java` | Order struct (`username`, `price`, `volume`, `side`, `timestamp`) |
| `Trade.java` | Trade struct (`seller`, `buyer`, `tradingPrice`, `quantity`) |
| `OrderBook.java` | **Your implementation goes here.** |
| `OrderBookTest.java` | Test harness — no need to modify. |

## API you need to implement

```java
ArrayList<Order> getAllOrdersAtPrice(long price)  // all orders resting at a price, either side; [] if none
ArrayList<Order> getAllOrders()                   // every resting order in the book

int getTotalVolumeAtPrice(long price)             // summed shares resting at a price, either side; 0 if none
int getTotalVolume()                              // summed shares resting in the whole book

int getBestBid()                                  // highest resting BUY price, or -1 if none
int getBestAsk()                                  // lowest resting ASK price, or -1 if none

boolean cancelOrder(long orderId)                 // true if found & removed, false otherwise

ArrayList<Trade> submitOrder(Order order)          // trades produced by this order; [] if it just rests
```

`getAllOrders()` / `getAllOrdersAtPrice()` and the `Trade` list returned by
`submitOrder()` have **no guaranteed order** — the test suite compares
their *contents*, not their sequence.

## Pro-rata matching rule

When an incoming order matches against a price level with multiple resting
orders:

1. Each resting order gets `floor(matchQty * theirShares / totalSharesAtLevel)`.
2. That floor step under-allocates by some remainder `R = matchQty - sum(floors)`.
3. `R` is handed out **one share at a time, in FIFO (submission) order**, to
   the first `R` orders at that level.

   Example: 6 shares left over 12 equally-weighted orders → the first 6
   orders (by submission order) each get exactly 1 extra share; the rest
   get 0.

A resting order that receives a fill of exactly `0` (floor was 0 and it
didn't receive part of the remainder) does **not** produce a `Trade` object.

## Running the tests

There are two test modes, since `cancelOrder` is a large enough feature to
verify on its own:

```bash
javac Side.java Order.java Trade.java OrderBook.java OrderBookTest.java

java OrderBookTest 0   # CORE suite: everything except cancelOrder
java OrderBookTest 1   # FULL suite: core tests + cancelOrder tests
```

Running with no argument (or anything other than `0`/`1`) prints usage and
exits without running anything:

```
Usage: java OrderBookTest <0|1>
  0 -> run the core suite only (no cancelOrder tests)
  1 -> run the full suite (core + cancelOrder tests)
```

The harness stops at the **first failing test** and reports how many passed
before that, e.g.:

```
[27/40] proRata_fifoRemainderToFirstOrder ... FAILED
  Reason: proRata_fifoRemainderToFirstOrder trades: trade vs resting order 1 (quantity) (expected 6, got 5)

=====================================
RESULTS: 26/40 test cases passed.
=====================================
```