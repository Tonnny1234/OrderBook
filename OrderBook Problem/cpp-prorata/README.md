# OrderBook (C++)

Implement the `OrderBook` class: a **pro-rata limit order book with FIFO
remainder allocation**.

## Files

| File | Description |
|---|---|
| `Side.h` | `enum class Side { BUY, ASK };` |
| `Order.h` | Order struct (`username`, `price`, `volume`, `side`, `timestamp`) |
| `Trade.h` | Trade struct (`seller`, `buyer`, `tradingPrice`, `quantity`) |
| `OrderBook.h` | Class declaration. |
| `OrderBook.cpp` | **Your implementation goes here.** |
| `main.cpp` | Test harness — no need to modify. |

## API you need to implement

```cpp
vector<Order> getAllOrdersAtPrice(unsigned long price);  // all orders resting at a price, either side; {} if none
vector<Order> getAllOrders();                            // every resting order in the book

unsigned long getTotalVolumeAtPrice(unsigned long price); // summed shares resting at a price, either side; 0 if none
unsigned long getTotalVolume();                           // summed shares resting in the whole book

long long getBestBid();  // highest resting BUY price, or -1 if none
long long getBestAsk();  // lowest resting ASK price, or -1 if none

long long cancelOrder(unsigned long orderId);  // the order's price if found & removed, -1 otherwise

vector<Trade> submitOrder(Order order);  // trades produced by this order; {} if it just rests
```

`getAllOrders()` / `getAllOrdersAtPrice()` and the `Trade` vector returned by
`submitOrder()` have **no guaranteed order** — the test suite compares their
*contents*, not their sequence.

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
didn't receive part of the remainder) does **not** produce a `Trade`.

## Running the tests

There are two test modes, since `cancelOrder` is a large enough feature to
verify on its own:

```bash
clang++ -std=c++23 -Wall -Werror -Wextra main.cpp OrderBook.cpp -o test

./test 0   # CORE suite: everything except cancelOrder
./test 1   # FULL suite: core tests + cancelOrder tests
```

Running with no argument (or anything other than `0`/`1`) prints usage and
exits without running anything:

```
Usage: ./test <0|1>
  0 -> run the core suite only (no cancelOrder tests)
  1 -> run the full suite (core + cancelOrder tests)
```

> **Heads up:** with `-Wunused-parameter -Werror` (implied by `-Wall -Wextra
> -Werror`), the *unimplemented stub* in `OrderBook.cpp` won't compile as-is,
> since its `TODO` bodies don't use their parameters yet. That resolves
> itself naturally as soon as you write real logic that reads `price`,
> `orderId`, `order`, etc. — nothing to "fix" ahead of time.

The harness stops at the **first failing test** and reports how many passed
before that, e.g.:

```
[27/40] proRata_fifoRemainderToFirstOrder ... FAILED
  Reason: proRata_fifoRemainderToFirstOrder trades: trade vs resting order 1 (quantity) (expected 6, got 5)

=====================================
RESULTS: 26/40 test cases passed.
=====================================
```

A `SIGSEGV`/`SIGABRT` handler also prints the last test name in flight if
your implementation crashes outright, so you know where to look even without
a debugger attached.