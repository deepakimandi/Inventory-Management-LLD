package com.lld.inventory.events;

import java.time.Instant;

public record StockEvent(StockEventType type, String warehouseId, String sku,
                         int quantity, int reorderLevel, Instant at) { }
