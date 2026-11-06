#include "OrderBookOptimized.h"
#include <algorithm>
#include <iterator>
#include <numeric>
#include <utility>

// O(log P + K), where K is the number of orders at this price.
vector<Order> OrderBook::getAllOrdersAtPrice(long price) {
    vector<Order> vs;
    const auto buy = buyOrders.find(price);
    const auto sell = sellOrders.find(price);
    vs.reserve((buy == buyOrders.end() ? 0 : buy->second.size()) +
               (sell == sellOrders.end() ? 0 : sell->second.size()));
    if(buy != buyOrders.end()){
        for(const auto& z : buy->second){
            vs.push_back(z);
        }
    }
    if(sell != sellOrders.end()){
        for(const auto& z : sell->second){
            vs.push_back(z);
        }
    }
    return vs;
}

// O(N). Copy only the orders returned to the caller.
vector<Order> OrderBook::getAllOrders() {
    vector<Order> vs;
    vs.reserve(idMap.size());
    for(const auto& z : buyOrders){
        for(const auto& zz : z.second){
            vs.push_back(zz);
        }
    }
    for(const auto& z : sellOrders){
        for(const auto& zz : z.second){
            vs.push_back(zz);
        }
    }
    return vs;
}

// O(log P).
int OrderBook::getTotalVolumeAtPrice(long price) {
    const auto vol = priceVolume.find(price);
    if(vol != priceVolume.end()){
        return vol->second;
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

// Timestamps are IDs. Expected O(log P), with expected O(1) ID lookups.
bool OrderBook::cancelOrder(long orderId) {
    const auto id = idMap.find(orderId);
    if(id == idMap.end())return false;
    const Loc lc = id->second;
    auto &book = lc.side == Side::BUY ? buyOrders : sellOrders;
    const auto lvl = book.find(lc.price);
    auto &vec = lvl->second;
    const int pr = vec[lc.index].volume;
    const auto vol = priceVolume.find(lc.price);
    vol->second -= pr;
    if(vol->second == 0){
        priceVolume.erase(vol);
    }
    tVolume -= pr;
    idMap.erase(id);
    // Move the tail into the removed slot; the deleted order need not be retained.
    if(lc.index != vec.size() - 1){
        vec[lc.index] = std::move(vec.back());
        idMap.find(vec[lc.index].timestamp)->second.index = lc.index;
    }
    vec.pop_back();
    if(vec.empty()){
        book.erase(lvl);
    }
    return true;
}

// Average O(M + (L + 1) log P), for M examined orders and L visited levels.
vector<Trade> OrderBook::submitOrder(Order order) {
    vector<Trade> res;
    auto& book = order.side == Side::BUY ? sellOrders : buyOrders;
    while(order.volume > 0 && !book.empty()){
        // If buying, take the lowest seller; if selling, take the highest buyer.
        const auto lvl = (order.side == Side::BUY) ? book.begin() : prev(book.end());
        const long price = lvl->first;
        if((order.side == Side::BUY && price > order.price) || (order.side == Side::ASK && price < order.price)){
            break;
        }
        const auto vol = priceVolume.find(price);
        const int total = vol->second;
        const int num = min(order.volume, total);
        auto &vec = lvl->second;
        // At most one trade per resting order and at most num nonzero trades.
        const size_t required = res.size() + min(vec.size(), static_cast<size_t>(num));
        if(res.capacity() < required){
            res.reserve(max(required, res.capacity() * 2));
        }
        if(num == total){
            // Trade all of it. Filled orders no longer need their username strings.
            for(auto &sat : vec){
                res.emplace_back(std::move(sat.username), order.username,
                                 static_cast<int>(price), sat.volume);
                idMap.erase(sat.timestamp);
            }
            book.erase(lvl);
            priceVolume.erase(vol);
        }else if(vec.size() == 1){
            // A single resting order gets the entire partial fill.
            res.emplace_back(vec[0].username, order.username, static_cast<int>(price), num);
            vec[0].volume -= num;
            vol->second -= num;
        }else if(num == 1){
            // All floors are zero; select the single remainder winner directly.
            size_t winner = 0;
            for(size_t i = 1; i < vec.size(); ++i){
                if(vec[i].volume > vec[winner].volume ||
                   (vec[i].volume == vec[winner].volume && vec[i].timestamp < vec[winner].timestamp)){
                    winner = i;
                }
            }
            res.emplace_back(vec[winner].username, order.username, static_cast<int>(price), 1);
            if(--vec[winner].volume == 0){
                idMap.erase(vec[winner].timestamp);
                if(winner != vec.size() - 1){
                    vec[winner] = std::move(vec.back());
                    idMap.find(vec[winner].timestamp)->second.index = winner;
                }
                vec.pop_back();
            }
            --vol->second;
        }else{
            const size_t n = vec.size();
            if(fill.size() < n)fill.resize(n);
            int rem = num;
            for(size_t i = 0; i < n; i++){
                fill[i] = static_cast<long long>(vec[i].volume) * num / total;
                rem -= fill[i];
            }
            const auto better = [&](size_t a, size_t b){
                return vec[a].volume == vec[b].volume ? vec[a].timestamp < vec[b].timestamp : vec[a].volume > vec[b].volume;
            };
            if(rem == 1){
                // Avoid filling and partitioning an index array for a single winner.
                size_t winner = 0;
                for(size_t i = 1; i < n; ++i){
                    if(better(i, winner))winner = i;
                }
                ++fill[winner];
            }else if(rem > 1){
                if(rnk.size() < n)rnk.resize(n);
                iota(rnk.begin(), rnk.begin() + n, size_t{0});
                nth_element(rnk.begin(), rnk.begin() + rem, rnk.begin() + n, better);
                for(int i = 0; i < rem; i++){
                    fill[rnk[i]]++;
                }
            }
            for(size_t i = n; i-- > 0;){
                if(fill[i] == 0)continue;
                res.emplace_back(vec[i].username, order.username, static_cast<int>(price), fill[i]);
                vec[i].volume -= fill[i];
                if(vec[i].volume == 0){
                    idMap.erase(vec[i].timestamp);
                    if(i != vec.size() - 1){
                        vec[i] = std::move(vec.back());
                        idMap.find(vec[i].timestamp)->second.index = i;
                    }
                    vec.pop_back();
                }
            }
            // This branch is a partial match, so the level cannot become empty.
            vol->second -= num;
        }
        tVolume -= num;
        order.volume -= num;
    }
    if(order.volume > 0){
        auto &vec = (order.side == Side::BUY ? buyOrders : sellOrders)[order.price];
        idMap.emplace(order.timestamp, Loc{order.price, vec.size(), order.side});
        priceVolume[order.price] += order.volume;
        tVolume += order.volume;
        vec.push_back(std::move(order));
    }
    return res;
}
