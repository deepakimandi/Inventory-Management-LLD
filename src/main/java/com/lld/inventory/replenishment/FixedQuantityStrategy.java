package com.lld.inventory.replenishment;

import com.lld.inventory.model.StockItem;

/** "Bulk order": always order the same large batch (fewer, bigger orders; think EOQ). */
public final class FixedQuantityStrategy implements ReplenishmentStrategy {
    private final int batchSize;

    public FixedQuantityStrategy(int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
        this.batchSize = batchSize;
    }

    @Override public int reorderQuantity(StockItem item) { return batchSize; }
    @Override public String name() { return "BULK(" + batchSize + ")"; }
}
