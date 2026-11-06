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
//O(logN) time compelxity
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
    if(static_cast<int>(lc.index) != static_cast<int>(vec.size()) - 1){
        swap(vec.back(), vec[lc.index]); 
        idMap[vec[lc.index].timestamp].index = lc.index; 
    }
    vec.pop_back();
    if(vec.empty()){
        book.erase(lc.price); 
    }
    return true;
}

//n + logn ish~ 
//O(N) we can assume! 
vector<Trade> OrderBook::submitOrder(Order order) {
    vector<Trade> res; 
    auto& book = order.side == Side::BUY ? sellOrders : buyOrders;
    while(order.volume > 0 && !book.empty()){
        //if my order is to buy, i want the lowest seller. 
        const auto lvl = (order.side == Side::BUY) ? book.begin() : prev(book.end());
        const long price = lvl->first;
        if((order.side == Side::BUY && price > order.price) || (order.side == Side::ASK && price < order.price)){
            break;
        }
        auto vol = priceVolume.find(price); 
        const int total = vol->second; 
        const int num = min(order.volume, total); 
        auto &vec = lvl->second; 
        if(num == total){
            //trade all of it
            for(auto &sat : vec){
                res.push_back(Trade(sat.username, order.username, price, sat.volume)); 
                idMap.erase(sat.timestamp); 
            }
            (book).erase(price);
            priceVolume.erase(price); 
        }else{
            vector<int> fill(vec.size()); 
            int rem = num;
            for(unsigned int i = 0; i < vec.size(); i++){
                fill[i] = (long long)vec[i].volume * num / total; 
                rem -= fill[i];
            }
            if(rem > 0){
                vector<int> rnk(vec.size());
                iota(rnk.begin(), rnk.end(), 0);
                nth_element(rnk.begin(),  rnk.begin() + rem, rnk.end(), [&](int a, int b){
                    return vec[a].volume == vec[b].volume ? vec[a].timestamp < vec[b].timestamp : vec[a].volume > vec[b].volume;
                });
                for(int i = 0; i < rem; i++){
                    fill[rnk[i]]++; 
                }
            }
            for(int i = vec.size() - 1; i >= 0; i--){
                if(fill[i] == 0)continue;
                res.push_back({vec[i].username, order.username, static_cast<int>(price), fill[i]});
                vec[i].volume -= fill[i]; 
                if (vec[i].volume == 0) {
                    idMap.erase(vec[i].timestamp);
                    if(static_cast<int>(i) != static_cast<int>(vec.size()) - 1) {
                        swap(vec[i], vec.back());
                        idMap[vec[i].timestamp].index = i;
                    }
                    vec.pop_back();
                }
            }
            priceVolume[price] -= num;
            if(priceVolume[price] == 0){
                priceVolume.erase(price); 
                book.erase(price); 
            }
        }
        tVolume -= num;
        order.volume -= num;
        
    }
    if(order.volume > 0){
        auto &vec = (order.side == Side::BUY ? buyOrders : sellOrders)[order.price]; 
        vec.push_back(order); 
        idMap[order.timestamp] = {order.price, static_cast<int>(vec.size() - 1), order.side}; 
        priceVolume[order.price] += order.volume; 
        tVolume += order.volume; 
    }
    return res;
}
