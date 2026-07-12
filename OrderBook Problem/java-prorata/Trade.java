/**
 * This trade class defines what we use to verify that a trade went through.
 * The test suite will use Trade objects to verify we have the correct matching
 * logic and solution.
 * 
 * restingOrderId - the ID of the Order that is being traded on
 * 
 * aggressiveOrderId - the ID of the Order that is making the trade
 * 
 * tradingPrice - the price that we are trading at 
 * 
 * quantity - how many shares we are trading at this price
 * 
 */

public class Trade {
    int restingOrderId;
    int aggressiveOrderId;
    int tradingPrice;
    int quantity;

    public Trade(Integer restingOrderId, Integer aggressiveOrderId, Integer tradingPrice, Integer quantity) {
        this.restingOrderId = restingOrderId;
        this.aggressiveOrderId = aggressiveOrderId;
        this.tradingPrice = tradingPrice;
        this.quantity = quantity;
    }

}