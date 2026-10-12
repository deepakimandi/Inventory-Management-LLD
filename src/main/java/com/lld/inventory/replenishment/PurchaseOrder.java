package com.lld.inventory.replenishment;

import java.time.Instant;

/** A request to a supplier. Immutable; "open" vs "received" is tracked by ReplenishmentService. */
public record PurchaseOrder(String id, String warehouseId, String sku, int quantity,
                            String strategy, Instant createdAt) {
    public String itemKey() { return warehouseId + "/" + sku; }
}
