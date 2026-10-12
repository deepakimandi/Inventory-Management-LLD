package com.lld.inventory.model;

import com.lld.inventory.exception.InsufficientStockException;

import java.util.concurrent.locks.ReentrantLock;

/**
 * HOW MANY of one SKU one warehouse holds, plus that warehouse's reorder settings for it.
 *
 * Concurrency:
 *  - every write happens while holding {@code lock} (check + update + snapshot are one atomic step);
 *  - {@code quantity} is volatile so readers (reports, dashboards) see the latest value WITHOUT taking the lock;
 *  - a transfer needs two items' locks; they are always taken in {@link #key()} order, so no deadlock.
 */
public final class StockItem {
    private final String warehouseId;
    private final String sku;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile int quantity;        // written only under lock
    private volatile int reorderLevel;    // "low stock" when quantity <= reorderLevel
    private volatile int maxLevel;        // how full we want the shelf after a top-up

    public StockItem(String warehouseId, String sku) {
        this.warehouseId = warehouseId;
        this.sku = sku;
    }

    public String warehouseId() { return warehouseId; }
    public String sku() { return sku; }
    public String key() { return warehouseId + "/" + sku; }
    public int quantity() { return quantity; }            // lock-free read
    public int reorderLevel() { return reorderLevel; }
    public int maxLevel() { return maxLevel; }
    public boolean isLow() { return quantity <= reorderLevel; }

    public void configure(int reorderLevel, int maxLevel) {
        if (reorderLevel < 0 || maxLevel < reorderLevel)
            throw new IllegalArgumentException("need 0 <= reorderLevel <= maxLevel");
        lock.lock();
        try {
            this.reorderLevel = reorderLevel;
            this.maxLevel = maxLevel;
        } finally {
            lock.unlock();
        }
    }

    public StockChange add(int units) {
        requirePositive(units);
        lock.lock();
        try {
            return applyLocked(units);
        } finally {
            lock.unlock();
        }
    }

    /** Check-then-act made atomic by the lock: no two threads can both see "10 left" and both take 10. */
    public StockChange remove(int units) {
        requirePositive(units);
        lock.lock();
        try {
            if (quantity < units) throw new InsufficientStockException(warehouseId, sku, units, quantity);
            return applyLocked(-units);
        } finally {
            lock.unlock();
        }
    }

    /** Moves units between two items atomically: either both change or neither does. */
    public static Transfer transfer(StockItem from, StockItem to, int units) {
        requirePositive(units);
        if (from == to) throw new IllegalArgumentException("source and destination are the same");
        // Global lock order (by key) => two opposite transfers can never wait on each other in a cycle.
        StockItem first = from.key().compareTo(to.key()) < 0 ? from : to;
        StockItem second = first == from ? to : from;
        first.lock.lock();
        try {
            second.lock.lock();
            try {
                if (from.quantity < units)
                    throw new InsufficientStockException(from.warehouseId, from.sku, units, from.quantity);
                return new Transfer(from.applyLocked(-units), to.applyLocked(units));
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    public record Transfer(StockChange out, StockChange in) { }

    private StockChange applyLocked(int delta) {
        int before = quantity;
        quantity = before + delta;
        return new StockChange(warehouseId, sku, before, quantity, reorderLevel);
    }

    private static void requirePositive(int units) {
        if (units <= 0) throw new IllegalArgumentException("units must be > 0, got " + units);
    }

    @Override public String toString() { return key() + " qty=" + quantity + " reorderAt=" + reorderLevel; }
}
