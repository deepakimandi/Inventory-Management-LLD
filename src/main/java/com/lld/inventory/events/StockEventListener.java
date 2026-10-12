package com.lld.inventory.events;

/** Observer. Implementations must be thread-safe: they can be called from many threads at once. */
@FunctionalInterface
public interface StockEventListener {
    void onEvent(StockEvent event);
}
