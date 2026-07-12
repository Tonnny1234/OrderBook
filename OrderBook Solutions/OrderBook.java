/*
This is one of the pro-rata Java solutions.
There are many more different variants that will pass all 40 test cases
*/

import java.util.*;




// TODO: if you need any supporting classes please add them here 

class PriceLevel {
    int totalVolume;
    LinkedList<Order> OrderList;
}

public class OrderBook {
    // TODO: add member variables here
    HashMap<Integer, PriceLevel> bidBook = new HashMap<>();
    HashMap<Integer, PriceLevel> askBook = new HashMap<>();

    PriorityQueue<Integer> bidPrices = new PriorityQueue<>(Collections.reverseOrder());
    PriorityQueue<Integer> askPrices = new PriorityQueue<>();



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
        
        ArrayList<Order> allOrders = new ArrayList<>();

        if ( (bidBook.get(price) == null) && (askBook.get(price) == null) ) {
            return allOrders;

        } else if ( bidBook.get(price) != null ) {
            
            PriceLevel p = bidBook.get(price);
            for ( Order o : p.OrderList ) {
                allOrders.add(o);
            }
            return allOrders;

        } else if ( askBook.get(price) != null ) {
            
            PriceLevel p = askBook.get(price);
            for ( Order o : p.OrderList ) {
                allOrders.add(o);
            }
            return allOrders;
        }

        return allOrders;
    }

    public ArrayList<Order> getAllOrders() {
        // TODO: implement logic
        
        ArrayList<Order> allOrders = new ArrayList<>();

        for ( PriceLevel p : bidBook.values() ) {

            for ( Order o : p.OrderList ) {
                allOrders.add(o);
            }

        }

        for ( PriceLevel p : askBook.values() ) {

            for ( Order o : p.OrderList ) {
                allOrders.add(o);
            }

        }

        return allOrders;
    }  

    /*
    These functions return the total volume at a price or in the whole book.

    If there are no orders at a price, return 0
    */
    public int getTotalVolumeAtPrice(Integer price) {
        // TODO: implement logic
        
        if ( (bidBook.get(price) == null) && (askBook.get(price) == null) ) {
            return 0;
        } else if ( bidBook.get(price) != null ) {
            return bidBook.get(price).totalVolume;
        } else if ( askBook.get(price) != null ) {
            return askBook.get(price).totalVolume;
        }
        return 0;
    }

    public int getTotalVolume() {
        // TODO: implement logic
        int totalVolume = 0;

        for ( PriceLevel p : bidBook.values() ) {
            totalVolume += p.totalVolume;
        }

        for ( PriceLevel p : askBook.values() ) {
            totalVolume += p.totalVolume;
        }

        return totalVolume;
    }

    /*
    These functions return the best bid and best ask of the OrderBook.
    If there is no best bid or best ask, return  -1
    */
    public int getBestBid() {
        // TODO: implement logic
        if ( bidPrices.isEmpty() ) {
            return -1;
        }
        return bidPrices.peek();
    }

    public int getBestAsk() {
        // TODO: implement logic
        if ( askPrices.isEmpty() ) {
            return -1;
        }
        return askPrices.peek();
    }

    /*
    This will cancel an order on the OrderBook. If the orderId does not exist,
    return false, otherwise return true
    */
    public boolean cancelOrder(long orderId) {
        // TODO: implement logic
        return false;
    }

    /*
    This will submit an order to the orderbook. Return an ArrayList of Trades.
    If no trading happens, return an empty ArrayList
    */
    public ArrayList<Trade> submitOrder(Order incoming) {
        // TODO: implement logic
        
        if ( incoming.side == Side.BUY ) {
            return matchBuyOrder(incoming);
        } else if ( incoming.side == Side.ASK ) {
            return matchAskOrder(incoming);
        }

        return new ArrayList<Trade>();
    }

    public ArrayList<Trade> matchBuyOrder(Order incoming) {
        ArrayList<Trade> tradeReceipts = new ArrayList<>();

        // 1. order crosses
        // 2. order does not cross

        while ( !askBook.isEmpty() && (incoming.shares > 0) && (incoming.price >= getBestAsk()) )  {

        // 1. Order.shares >= totVolume
        // 2. Order.shares < totVolume


        // Case 1:
        // create receipts
        // decrease shares of incoming order
        // remove price level (prices & book)
        // try again

        // Case 2:
        // calculate the pro rata for matchingOrders (1)
        // give out remainder if any  (2)
        // apply fill (2)
        // calculate receipts (2)
        // decrease totalVolume (2)
        // remove any orders if needed (3)
        // decrease inc.qty (goes to 0)

            PriceLevel oppLevel = askBook.get(getBestAsk());

            if ( incoming.shares >= oppLevel.totalVolume ) {

                for ( Order resting : oppLevel.OrderList ) {
                    Trade t = new Trade(resting.orderId, incoming.orderId, resting.price, resting.shares);
                    tradeReceipts.add(t);
                }

                incoming.shares -= oppLevel.totalVolume;

                askBook.remove(getBestAsk());
                askPrices.remove();

            } else {

                HashMap<Order, Integer> fillQty = new HashMap<>();
                int allocated = 0;

                //calculate mathcing qty
                for ( Order resting : oppLevel.OrderList ) {
                    int restingAlloc = (incoming.shares * resting.shares)/oppLevel.totalVolume;
                    allocated += restingAlloc;
                    fillQty.put(resting, restingAlloc);
                }

                int remainder = incoming.shares - allocated;
                
                //give out remainder --> (apply fill, calc receipts, dec totVol)
                for ( Order resting : oppLevel.OrderList ) {

                    int currQty = fillQty.get(resting);

                    if ( remainder > 0 ) {
                        remainder--;
                        currQty += 1;
                    }

                    if ( currQty > 0 ) {
                        resting.shares -= currQty;
                        Trade t  = new Trade(resting.orderId, incoming.orderId, resting.price, currQty);
                        tradeReceipts.add(t);
                        oppLevel.totalVolume -= currQty;
                    }

                }

                //remove any orders with shares = 0
                Iterator<Order> it = oppLevel.OrderList.iterator();
                while ( it.hasNext() ) {
                    Order o = it.next();

                    if ( o.shares == 0 ) {
                        it.remove();
                    }
                }
                incoming.shares = 0;
            }

        }

        // 1. order was not filled (add)
        // 2. order was partially filled (add)
        // 3. order was fully filled  (skip)
        if ( incoming.shares > 0 ) {
            //is the price in the bidBook?
            if ( bidBook.get(incoming.price) == null ) {
                //add it to the bidBook and bidPrices
                bidPrices.add(incoming.price);
                PriceLevel p = new PriceLevel();
                p.OrderList = new LinkedList<>();
                bidBook.put(incoming.price, p);
            }

            //we need to update OrderList and totVolume
            PriceLevel p = bidBook.get(incoming.price);
            p.totalVolume += incoming.shares;
            p.OrderList.add(incoming);
        }
        return tradeReceipts;
    }

    public ArrayList<Trade> matchAskOrder(Order incoming) {
        ArrayList<Trade> tradeReceipts = new ArrayList<>();

        // 1. order crosses
        // 2. order does not cross

        while ( !bidBook.isEmpty() && (incoming.shares > 0) && (incoming.price <= getBestBid()) ) {

        // 1. Order.shares >= totVolume
        // 2. Order.shares < totVolume


        // Case 1:
        // create receipts
        // decrease shares of incoming order
        // remove price level (prices & book)
        // try again

        // Case 2:
        // calculate the pro rata for matchingOrders (1)
        // give out remainder if any  (2)
        // apply fill (2)
        // calculate receipts (2)
        // decrease totalVolume (2)
        // remove any orders if needed (3)
        // decrease inc.qty (goes to 0)

            PriceLevel oppLevel = bidBook.get(getBestBid());

            if ( incoming.shares >= oppLevel.totalVolume ) {

                for ( Order resting : oppLevel.OrderList ) {
                    Trade t = new Trade(resting.orderId, incoming.orderId, resting.price, resting.shares);
                    tradeReceipts.add(t);
                }

                incoming.shares -= oppLevel.totalVolume;

                bidBook.remove(getBestBid());
                bidPrices.remove();

            } else {

                HashMap<Order, Integer> fillQty = new HashMap<>();
                int allocated = 0;

                //calculate mathcing qty
                for ( Order resting : oppLevel.OrderList ) {
                    int restingAlloc = (incoming.shares * resting.shares)/oppLevel.totalVolume;
                    allocated += restingAlloc;
                    fillQty.put(resting, restingAlloc);
                }

                int remainder = incoming.shares - allocated;
                
                //give out remainder --> (apply fill, calc receipts, dec totVol)
                for ( Order resting : oppLevel.OrderList ) {

                    int currQty = fillQty.get(resting);

                    if ( remainder > 0 ) {
                        remainder--;
                        currQty += 1;
                    }

                    if ( currQty > 0 ) {
                        resting.shares -= currQty;
                        Trade t  = new Trade(resting.orderId, incoming.orderId, resting.price, currQty);
                        tradeReceipts.add(t);
                        oppLevel.totalVolume -= currQty;
                    }

                }

                //remove any orders with shares = 0
                Iterator<Order> it = oppLevel.OrderList.iterator();
                while ( it.hasNext() ) {
                    Order o = it.next();

                    if ( o.shares == 0 ) {
                        it.remove();
                    }
                }
                incoming.shares = 0;
            }
            
        }

        // 1. order was not filled (add)
        // 2. order was partially filled (add)
        // 3. order was fully filled  (skip)
        if ( incoming.shares > 0 ) {
            //is the price in the bidBook?
            if ( askBook.get(incoming.price) == null ) {
                //add it to the askBook and askPrices
                askPrices.add(incoming.price);
                PriceLevel p = new PriceLevel();
                p.OrderList = new LinkedList<>();
                askBook.put(incoming.price, p);
            }

            //we need to update OrderList and totVolume
            PriceLevel p = askBook.get(incoming.price);
            p.totalVolume += incoming.shares;
            p.OrderList.add(incoming);
        }
        return tradeReceipts;
    }

}