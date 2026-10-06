#pragma once

#include "Order.h"
#include "Trade.h"
#include <vector>

using namespace std;

// Template OrderBook: implementation removed for the exercise.
// Students should implement the pro-rata limit order book behavior here.

class OrderBook {
private:
    // TODO: add member variables here

public:
    /*
    These are the list of functions that you need to support and what the test
    suite will test you on. The bodies (in OrderBook.cpp) are intentionally
    left as stubs so students can implement their own solution.
    */

    // Return all orders resting at a given price (both sides). Empty vector if none.
    vector<Order> getAllOrdersAtPrice(long price);

    // Return every resting order in the book (no particular order required).
    vector<Order> getAllOrders();

    // Total resting volume at a price (both sides). 0 if none.
    int getTotalVolumeAtPrice(long price);

    // Total resting volume in the whole book.
    int getTotalVolume();

    // Highest resting BUY price, or -1 if none.
    int getBestBid();

    // Lowest resting ASK price, or -1 if none.
    int getBestAsk();

    // Cancel an order by numeric id. Return true if found and removed, false otherwise.
    bool cancelOrder(long orderId);

    // Submit an order and return the vector of Trades produced by this call.
    vector<Trade> submitOrder(Order order);
};
