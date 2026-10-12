package com.lld.inventory.replenishment;

import com.lld.inventory.model.StockItem;

/** "Just-in-time": order exactly enough to refill the shelf to maxLevel (good for perishables). */
public final class TopUpToMaxStrategy implements ReplenishmentStrategy {
    @Override public int reorderQuantity(StockItem item) { return Math.max(0, item.maxLevel() - item.quantity()); }
    @Override public String name() { return "TOP_UP"; }
}
