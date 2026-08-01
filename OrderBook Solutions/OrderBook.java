import java.util.*;


class PriceLevel {
    HashSet<Order> orderCollection;
    Integer totalVolume;

    PriceLevel () {
        this.totalVolume = 0;
        orderCollection = new HashSet<>();
    }
}

// Comparator used for remainder allocation: volume desc, then timestamp asc
class OrderComparators {
    public static final Comparator<Order> RemainderAlloc = (o1, o2) -> {
        if (o1.volume > o2.volume) return -1;
        if (o1.volume < o2.volume) return 1;
        return Long.compare(o1.timestamp, o2.timestamp);
    };
}

// TODO: if you need any supporting classes please add them here 

public class OrderBook {
    // TODO: add member variables here

    /*
    These are the list of functions that you need to support and what the test
    suite will test you on.
    */
    
    HashMap<Integer, PriceLevel> bidBook = new HashMap<>();
    PriorityQueue<Integer> bidPrices = new PriorityQueue<>(Collections.reverseOrder());

    HashMap<Integer, PriceLevel> askBook = new HashMap<>();
    PriorityQueue<Integer> askPrices = new PriorityQueue<>();

    // map username -> Order for quick cancellation/lookup. usernames are "u<ID>" in tests
    HashMap<String, Order> allOrdersOnBook = new HashMap<>();

    Integer time = 0;


    /*
    These functions return all of the orders in the whole book or at a price
    level. The order in which the Orders are in the vector do not matter,
    so long as they collection of Orders are the same.

    If there are no orders at a price, return an empty vector
    */
    public ArrayList<Order> getAllOrdersAtPrice(Integer price) {
        // TODO: implement logic
        ArrayList<Order> allOrders = new ArrayList<>();

        if ( bidBook.get(price) != null ) {
            PriceLevel p = bidBook.get(price);
            for ( Order entry : p.orderCollection ) {
                allOrders.add(entry);
            }
        }

        if ( askBook.get(price) != null ) {
            PriceLevel p = askBook.get(price);
            for ( Order entry : p.orderCollection ) {
                allOrders.add(entry);
            }
        }
        
        return allOrders;
    }

    public ArrayList<Order> getAllOrders() {
        // TODO: implement logic
        ArrayList<Order> allOrders = new ArrayList<Order>();

        for ( PriceLevel level : bidBook.values() ) {
            for ( Order e : level.orderCollection ) {
                allOrders.add(e);
            }
        }

        for ( PriceLevel level : askBook.values() ) {
            for ( Order e : level.orderCollection ) {
                allOrders.add(e);
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
        if ( bidBook.get(price) != null ) {
            PriceLevel p = bidBook.get(price);
            return p.totalVolume;
        }

        if ( askBook.get(price) != null ) {
            PriceLevel p = askBook.get(price);
            return p.totalVolume;
        }
        
        return 0;
    }

    public int getTotalVolume() {
        // TODO: implement logic
        int total = 0;

        for ( PriceLevel level : bidBook.values() ) {
            total += level.totalVolume;
        }

        for ( PriceLevel level : askBook.values() ) {
            total += level.totalVolume;
        }

        return total;
    }

    /*
    These functions return the best bid and best ask of the OrderBook.
    If there is no best bid or best ask, return  -1
    */
    public int getBestBid() {
        // TODO: implement logic
        while ( !bidPrices.isEmpty() ) {
            Integer price = bidPrices.peek();
            if ( bidBook.get(price) != null ) {
                return price;
            }
            bidPrices.remove();
        }
        return -1;
    }

    public int getBestAsk() {
        // TODO: implement logic
        while ( !askPrices.isEmpty() ) {
            Integer price = askPrices.peek();
            if ( askBook.get(price) != null ) {
                return price;
            }
            askPrices.remove();
        }
        return -1;
    }

    /*
    This will cancel an order on the OrderBook. If the orderId does not exist,
    return false, otherwise return true
    */
    public boolean cancelOrder(Integer orderId) {
        String uname = "u" + orderId;
        Order entry = allOrdersOnBook.get(uname);
        if ( entry == null ) {
            return false;
        }

        HashMap<Integer, PriceLevel> book = (entry.side == Side.BUY) ? bidBook : askBook;
        PriceLevel p = book.get(entry.price);
        p.totalVolume -= entry.volume;
        p.orderCollection.remove(entry);

        if ( p.totalVolume == 0 ) {
            book.remove(entry.price);
        }

        allOrdersOnBook.remove(uname);

        return true;
    }

    /*
    This will submit an order to the orderbook. Return an ArrayList of Trades.
    If no trading happens, return an empty ArrayList
    */
    public ArrayList<Trade> submitOrder(Order incoming) {
        // TODO: implement logic
        
        if (incoming.side == Side.BUY) {
            return matchBuyOrder(incoming);
        } else {
            return matchSellOrder(incoming);
        }
    }

    private ArrayList<Trade> matchBuyOrder(Order incoming) {
        ArrayList<Trade> tradeReciepts = new ArrayList<>();

        while ( !askBook.isEmpty() && incoming.price >= getBestAsk() && incoming.volume > 0 ) {

            PriceLevel oppLevel = askBook.get(getBestAsk());

            if ( incoming.volume >= oppLevel.totalVolume ) {
                //create receipts
                for ( Order o : oppLevel.orderCollection ) {
                    Trade t = new Trade(o.username, incoming.username, o.price, o.volume);
                    tradeReciepts.add(t);
                }

                //dec inc shares
                incoming.volume -= oppLevel.totalVolume;

                //remove price and collection of orders 
                askBook.remove(getBestAsk());
                askPrices.remove();
            } else {

                HashMap<Order, Integer> fillQty = new HashMap<>();
                PriorityQueue<Order> remainderQueue = new PriorityQueue<>(OrderComparators.RemainderAlloc.reversed());
                int allocated = 0;

                //calc pro rata
                for ( Order e : oppLevel.orderCollection ) {
                    int orderAlloc = (e.volume * incoming.volume)/oppLevel.totalVolume;
                    fillQty.put(e, orderAlloc);
                    allocated += orderAlloc;
                }

                //find orders eligible for remainder if it exists
                int remainder = incoming.volume - allocated;
                if ( remainder > 0 ) {
                    for ( Order e : oppLevel.orderCollection ) {
                        remainderQueue.add(e);

                        if ( remainderQueue.size() > remainder ) {
                            remainderQueue.remove();
                        }
                    }
                }

                //go thru the remainders and update fill qty
                while ( !remainderQueue.isEmpty() ) {
                    Order entry = remainderQueue.poll();
                    
                    Integer currQty = fillQty.get(entry);
                    fillQty.put(entry, currQty+1);
                }

                //apply fills and create trades
                ArrayList<Order> toRemove = new ArrayList<>();
                for ( Order e : oppLevel.orderCollection ) {

                    int tradeQty = fillQty.get(e);

                    if ( tradeQty > 0 ) {
                        Trade t = new Trade(e.username, incoming.username, e.price, tradeQty);
                        tradeReciepts.add(t);

                        incoming.volume -= tradeQty;
                        oppLevel.totalVolume -= tradeQty;
                        e.volume -= tradeQty;

                        if ( e.volume == 0 ) {
                            allOrdersOnBook.remove(e.username);
                            toRemove.add(e);
                        }
                    }
                }

                for ( Order e : toRemove ) {
                    oppLevel.orderCollection.remove(e);
                }
            }
        }

        
        if ( incoming.volume > 0 ) {
            if ( bidBook.get(incoming.price) == null ) {
                PriceLevel p = new PriceLevel();
                bidBook.put(incoming.price, p);
                bidPrices.add(incoming.price);
            }
            PriceLevel p = bidBook.get(incoming.price);
            p.orderCollection.add(incoming);
            p.totalVolume += incoming.volume;
            allOrdersOnBook.put(incoming.username, incoming);
        }
        return tradeReciepts;
    }

    private ArrayList<Trade> matchSellOrder(Order incoming) {
        ArrayList<Trade> tradeReciepts = new ArrayList<>();

        while ( !bidBook.isEmpty() && incoming.price <= getBestBid() && incoming.volume > 0 ) {

            PriceLevel oppLevel = bidBook.get(getBestBid());

            if ( incoming.volume >= oppLevel.totalVolume ) {
                //create receipts
                for ( Order o : oppLevel.orderCollection ) {
                    Trade t = new Trade(o.username, incoming.username, o.price, o.volume);
                    tradeReciepts.add(t);
                }

                //dec inc shares
                incoming.volume -= oppLevel.totalVolume;

                //remove price and collection of orders 
                bidBook.remove(getBestBid());
                bidPrices.remove();
            } else {

                HashMap<Order, Integer> fillQty = new HashMap<>();
                PriorityQueue<Order> remainderQueue = new PriorityQueue<>(OrderComparators.RemainderAlloc.reversed());
                int allocated = 0;

                //calc pro rata
                for ( Order e : oppLevel.orderCollection ) {
                    int orderAlloc = (e.volume * incoming.volume)/oppLevel.totalVolume;
                    fillQty.put(e, orderAlloc);
                    allocated += orderAlloc;
                }

                //find orders eligible for remainder if it exists
                int remainder = incoming.volume - allocated;
                if ( remainder > 0 ) {
                    for ( Order e : oppLevel.orderCollection ) {
                        remainderQueue.add(e);

                        if ( remainderQueue.size() > remainder ) {
                            remainderQueue.remove();
                        }
                    }
                }

                //go thru the remainders and update fill qty
                while ( !remainderQueue.isEmpty() ) {
                    Order entry = remainderQueue.poll();
                    
                    Integer currQty = fillQty.get(entry);
                    fillQty.put(entry, currQty+1);
                }

                //apply fills and create trades
                ArrayList<Order> toRemove = new ArrayList<>();
                for ( Order e : oppLevel.orderCollection ) {

                    int tradeQty = fillQty.get(e);

                    if ( tradeQty > 0 ) {
                        Trade t = new Trade(e.username, incoming.username, e.price, tradeQty);
                        tradeReciepts.add(t);

                        incoming.volume -= tradeQty;
                        oppLevel.totalVolume -= tradeQty;
                        e.volume -= tradeQty;

                        if ( e.volume == 0 ) {
                            allOrdersOnBook.remove(e.username);
                            toRemove.add(e);
                        }
                    }
                }

                for ( Order e : toRemove ) {
                    oppLevel.orderCollection.remove(e);
                }
            }
        }

        
        if ( incoming.volume > 0 ) {
            if ( askBook.get(incoming.price) == null ) {
                PriceLevel p = new PriceLevel();
                askBook.put(incoming.price, p);
                askPrices.add(incoming.price);
            }
            PriceLevel p = askBook.get(incoming.price);
            p.orderCollection.add(incoming);
            p.totalVolume += incoming.volume;
            allOrdersOnBook.put(incoming.username, incoming);
        }
        return tradeReciepts;
    }

}