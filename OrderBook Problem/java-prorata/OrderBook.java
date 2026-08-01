import java.util.*;

// Template OrderBook: implementation removed for the exercise.
// Students should implement the pro-rata limit order book behavior here.

public class OrderBook {

    // TODO: add member variables here

    /*
    These are the list of functions that you need to support and what the test
    suite will test you on. The bodies are intentionally left as stubs so
    students can implement their own solution.
    */

    // Return all orders resting at a given price (both sides). Empty list if none.
    public ArrayList<Order> getAllOrdersAtPrice(long price) {
        // TODO: implement logic
        return new ArrayList<>();
    }

    // Return every resting order in the book (no particular order required).
    public ArrayList<Order> getAllOrders() {
        // TODO: implement logic
        return new ArrayList<>();
    }

    // Total resting volume at a price (both sides). 0 if none.
    public int getTotalVolumeAtPrice(long price) {
        // TODO: implement logic
        return 0;
    }

    // Total resting volume in the whole book.
    public int getTotalVolume() {
        // TODO: implement logic
        return 0;
    }

    // Highest resting BUY price, or -1 if none.
    public int getBestBid() {
        // TODO: implement logic
        return -1;
    }

    // Lowest resting ASK price, or -1 if none.
    public int getBestAsk() {
        // TODO: implement logic
        return -1;
    }

    // Cancel an order by numeric id. Return true if found and removed, false otherwise.
    public boolean cancelOrder(long orderId) {
        // TODO: implement logic
        return false;
    }

    // Submit an order and return the list of Trades produced by this call.
    public ArrayList<Trade> submitOrder(Order order) {
        // TODO: implement logic
        return new ArrayList<>();
    }

}
