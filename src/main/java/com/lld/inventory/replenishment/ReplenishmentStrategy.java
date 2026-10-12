package com.lld.inventory.replenishment;

import com.lld.inventory.model.StockItem;

/** Strategy: HOW MUCH to reorder when an item runs low. Pure function, no side effects. */
public interface ReplenishmentStrategy {
    int reorderQuantity(StockItem item);
    String name();
}
