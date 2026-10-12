package com.lld.inventory.audit;

import java.time.Instant;

/**
 * One immutable line of the audit trail (a ledger entry). Never updated, never deleted.
 * before/after make every row self-describing, so the trail can be replayed and checked.
 */
public record StockTransaction(long id, Instant at, TransactionType type, String warehouseId, String sku,
                               int delta, int before, int after, String actorId, String reference) {
    @Override public String toString() {
        return String.format("#%-3d %-12s %-4s %-9s %+5d  %4d -> %-4d by %-8s ref=%s",
                id, type, warehouseId, sku, delta, before, after, actorId, reference);
    }
}
