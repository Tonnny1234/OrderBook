#pragma once

#include <string>
#include <utility>

/**
 * Trade record used by the test harness.
 *
 * seller  - username of the resting (liquidity-providing) side
 * buyer   - username of the aggressive (taker) side
 * tradingPrice - price the trade executed at
 * quantity     - number of units traded
 */

struct Trade {
    std::string seller;
    std::string buyer;
    int tradingPrice;
    int quantity;

    Trade(std::string seller, std::string buyer, int tradingPrice, int quantity)
        : seller(std::move(seller)), buyer(std::move(buyer)),
          tradingPrice(tradingPrice), quantity(quantity) {}
};
