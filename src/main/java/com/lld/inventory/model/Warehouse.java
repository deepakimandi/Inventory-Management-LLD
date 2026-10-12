package com.lld.inventory.model;

import com.lld.inventory.exception.NotFoundException;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A physical location holding stock. Owns its StockItems (composition).
 * The map is shared by ALL SKUs, so it must be a ConcurrentHashMap even though each item has its own lock.
 * Items are never removed at zero quantity: they keep their reorder settings and their identity
 * (a thread holding a reference can't end up updating a detached object).
 */
public final class Warehouse {
    private final String id;
    private final String name;
    private final String location;
    private final Map<String, StockItem> items = new ConcurrentHashMap<>();

    public Warehouse(String id, String name, String location) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("warehouse id is blank");
        this.id = id;
        this.name = Objects.requireNonNull(name);
        this.location = Objects.requireNonNull(location);
    }

    public String id() { return id; }
    public String name() { return name; }
    public String location() { return location; }

    /** Atomic get-or-create: two threads receiving a new SKU at once still get the SAME StockItem. */
    public StockItem stockFor(String sku) {
        return items.computeIfAbsent(sku, s -> new StockItem(id, s));
    }

    public Optional<StockItem> find(String sku) { return Optional.ofNullable(items.get(sku)); }

    public StockItem require(String sku) {
        return find(sku).orElseThrow(() -> new NotFoundException("Stock item", id + "/" + sku));
    }

    public int quantityOf(String sku) { return find(sku).map(StockItem::quantity).orElse(0); }

    public Collection<StockItem> items() { return Collections.unmodifiableCollection(items.values()); }

    @Override public String toString() { return id + " (" + name + ", " + location + ")"; }
}
