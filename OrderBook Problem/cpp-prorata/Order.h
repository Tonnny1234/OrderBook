#pragma once

#include "Side.h"
#include <string>

/**
 * This defines what an Order on the OrderBook actually is.
 *
 * username  - a unique string identifier for the user submitting the order
 * price     - the limit price for the order
 * volume    - how many units the order is for
 * side      - BUY or ASK
 * timestamp - unique timestamp for every order (used for tie-breaking)
 */

struct Order {
    std::string username;
    unsigned long price;
    unsigned long volume;
    Side side;
    unsigned long timestamp;
};