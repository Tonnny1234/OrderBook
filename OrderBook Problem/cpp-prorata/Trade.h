#pragma once

/**
 * This trade struct defines what we use to verify that a trade went through.
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
 */

struct Trade {
    unsigned long restingOrderId;
    unsigned long aggressiveOrderId;
    unsigned long tradingPrice;
    unsigned long quantity;
};