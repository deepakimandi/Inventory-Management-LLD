package com.lld.inventory.audit;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryAuditLog implements AuditLog {
    private final Queue<StockTransaction> entries = new ConcurrentLinkedQueue<>();   // lock-free append
    private final AtomicLong ids = new AtomicLong();

    @Override public void append(StockTransaction tx) { entries.add(tx); }
    @Override public List<StockTransaction> all() { return List.copyOf(entries); }
    @Override public List<StockTransaction> forSku(String sku) {
        return entries.stream().filter(t -> t.sku().equals(sku)).toList();
    }
    @Override public long nextId() { return ids.incrementAndGet(); }
}
