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

int** clone(int **a){
   int **b = new int*[2];  
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

bool OrderBook::cancelOrder(long orderId) {
    // TODO: implement logic
    return false;
}

vector<Trade> OrderBook::submitOrder(Order order) {
    // TODO: implement logic
    return {};
}
