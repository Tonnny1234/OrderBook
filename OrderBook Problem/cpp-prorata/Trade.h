#pragma once

#include <string>

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
    unsigned long tradingPrice;
    unsigned long quantity;
};