#include "OrderBook.h"

//O(N)
vector<Order> OrderBook::getAllOrdersAtPrice(long price) {
    vector<Order> vs = {};
    if(buyOrders.find(price) != buyOrders.end()){
        for(auto z : buyOrders[price]){
            vs.push_back(z); 
        }
    }
    if(sellOrders.find(price) != sellOrders.end()){
        for(auto z : sellOrders[price]){
            vs.push_back(z); 
        }
    }
    return vs; 
}

//O(N)
vector<Order> OrderBook::getAllOrders() {
    vector<Order> vs;
    for(auto z : buyOrders){
        for(auto zz : z.second){
            vs.push_back(zz); 
        }
    }
    for(auto z : sellOrders){
        for(auto zz : z.second){
            vs.push_back(zz); 
        }
    }
    return vs; 
}

//O(logN) 
int OrderBook::getTotalVolumeAtPrice(long price) {
    if(priceVolume.find(price) != priceVolume.end()){
        return priceVolume[price]; 
    }
    return 0;
}

//O(1)
int OrderBook::getTotalVolume() {
    return tVolume; 
}

//O(1)
int OrderBook::getBestBid() {
    if(buyOrders.empty()){
        return -1; 
    }
    return buyOrders.rbegin()->first; 
}

//O(1)
int OrderBook::getBestAsk() {
    if(sellOrders.empty()){
        return -1; 
    }
    return sellOrders.begin()->first; 
}

//timestamps is equivalent to ID
//update the map, totalvolume, and volume at price 

bool OrderBook::cancelOrder(long orderId) {
    if(idMap.find(orderId) == idMap.end())return false;
    Loc lc = idMap[orderId]; 
    auto &book = lc.side == Side::BUY ? buyOrders : sellOrders; 
    auto &vec = book[lc.price]; 
    long pr = vec[lc.index].volume; 
    priceVolume[lc.price] -= pr; 
    if(priceVolume[lc.price] == 0){
        priceVolume.erase(lc.price); 
    }
    tVolume -= pr; 
    idMap.erase(orderId); 
    //swap and pop 
    if(lc.index != vec.size() - 1){
        swap(vec.back(), vec[lc.index]); 
        idMap[vec[lc.index].timestamp].index = lc.index; 

    }
    vec.pop_back();
    if(vec.empty()){
        book.erase(lc.price); 
    }
    return true;
}

vector<Trade> OrderBook::submitOrder(Order order) {
    
    return {};
}
