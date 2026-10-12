package com.lld.inventory.audit;

import java.util.List;

/** Append-only. Swap the in-memory version for a DB table without touching the service. */
public interface AuditLog {
    void append(StockTransaction tx);
    List<StockTransaction> all();
    List<StockTransaction> forSku(String sku);
    long nextId();
}
