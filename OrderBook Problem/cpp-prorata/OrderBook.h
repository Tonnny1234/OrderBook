#include "Order.h"
#include "Trade.h"
#include <vector>


using namespace std;

class OrderBook {
private:
    /*
    All variables go here 
    */

public:
    /*
    These are the list of functions that you need to support and what the test
    suite will test you on.

    This is a prorata orderbook. The remainder
    is distributed FIFO
    */

    /*
    These functions return all of the orders in the whole book or at a price
    level. The order in which the Orders are in the vector do not matter,
    so long as they collection of Orders are the same.

    If there are no orders at a price, return an empty vector
    */
    vector<Order> getAllOrdersAtPrice(unsigned long price);
    vector<Order> getAllOrders();


    /*
    These functions return the total volume at a price or in the whole book.

    If there are no orders at a price, return 0
    */
    unsigned long getTotalVolumeAtPrice(unsigned long price);
    unsigned long getTotalVolume();


    /*
    These functions return the best bid and best ask of the OrderBook.
    If there is no best bid or best ask, return  -1
    */
    long long getBestBid();
    long long getBestAsk();

    /* 
    This will cancel an order on the OrderBook. If the orderId does not exist,
    return -1, otherwise return the price associated with the order
    */
    long long cancelOrder(unsigned long orderId);
    
    
    /*
    This will submit an order to the orderbook. Return a vector of Trades
    produced by this order. If no trading happens, return an empty vector.
    */
    vector<Trade> submitOrder(Order order);
};