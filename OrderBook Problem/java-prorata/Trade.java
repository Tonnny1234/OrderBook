/**
 * Trade record used by the test harness.
 *
 * seller  - username of the resting (liquidity-providing) side
 * buyer   - username of the aggressive (taker) side
 * tradingPrice - price the trade executed at
 * quantity     - number of units traded
 */

public class Trade {
    public String seller;
    public String buyer;
    public int tradingPrice;
    public int quantity;

    public Trade(String seller, String buyer, Integer tradingPrice, Integer quantity) {
        this.seller = seller;
        this.buyer = buyer;
        this.tradingPrice = tradingPrice;
        this.quantity = quantity;
    }

}