package com.lld.inventory.model;

/**
 * Result of one quantity change, captured INSIDE the item's lock, so before/after are exact.
 * The checks are "edge-triggered": true only for the one change that crosses the line, not for every change after it.
 */
public record StockChange(String warehouseId, String sku, int before, int after, int reorderLevel) {

    /** Was above the reorder level, now at or below it. Fires once per crossing, not on every sale. */
    public boolean crossedIntoLowStock() { return before > reorderLevel && after <= reorderLevel; }

    public boolean ranOut() { return before > 0 && after == 0; }

    /** Was empty, now sellable again (e.g. to notify "tell me when it's available" customers). */
    public boolean restocked() { return before == 0 && after > 0; }
}
