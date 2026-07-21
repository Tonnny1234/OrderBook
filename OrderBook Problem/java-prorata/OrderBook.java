import java.util.*;




// TODO: if you need any supporting classes please add them here 

public class OrderBook {
    // TODO: add member variables here

    /*
    These are the list of functions that you need to support and what the test
    suite will test you on.
    */



    /*
    These functions return all of the orders in the whole book or at a price
    level. The order in which the Orders are in the vector do not matter,
    so long as they collection of Orders are the same.

    If there are no orders at a price, return an empty vector
    */
    public ArrayList<Order> getAllOrdersAtPrice(Integer price) {
        // TODO: implement logic
        return new ArrayList<Order>();
    }

    public ArrayList<Order> getAllOrders() {
        // TODO: implement logic
        return new ArrayList<Order>();
    }  

    /*
    These functions return the total volume at a price or in the whole book.

    If there are no orders at a price, return 0
    */
    public int getTotalVolumeAtPrice(Integer price) {
        // TODO: implement logic
        return 0;
    }

    public int getTotalVolume() {
        // TODO: implement logic
        return 0;
    }

    /*
    These functions return the best bid and best ask of the OrderBook.
    If there is no best bid or best ask, return  -1
    */
    public int getBestBid() {
        // TODO: implement logic
        return -1;
    }

    public int getBestAsk() {
        // TODO: implement logic
        return -1;
    }

    /*
    This will cancel an order on the OrderBook. If the orderId does not exist,
    return false, otherwise return true
    */
    public boolean cancelOrder(Integer orderId) {
        // TODO: implement logic
        return false;
    }

    /*
    This will submit an order to the orderbook. Return an ArrayList of Trades.
    If no trading happens, return an empty ArrayList
    */
    public ArrayList<Trade> submitOrder(Order incoming) {
        // TODO: implement logic
        return new ArrayList<Trade>();
    }

}