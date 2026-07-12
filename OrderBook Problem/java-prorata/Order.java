/**
 * This defines what an Order on the OrderBook actually is.
 * 
 * orderId - a unique identifier of every Order
 * 
 * price - the maximum price (if it is a buy) it will trade at or the 
 *         minimum price (if it is a sell) it will trade at
 * 
 * side - the side of the book that the Order is on 
 */


public class Order {

    public Integer orderId;
    public Integer price;
    public Integer shares;
    public Side side;

}