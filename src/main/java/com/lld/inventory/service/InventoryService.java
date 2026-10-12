package com.lld.inventory.service;

import com.lld.inventory.audit.AuditLog;
import com.lld.inventory.audit.StockTransaction;
import com.lld.inventory.audit.TransactionType;
import com.lld.inventory.events.StockEvent;
import com.lld.inventory.events.StockEventPublisher;
import com.lld.inventory.events.StockEventType;
import com.lld.inventory.model.Permission;
import com.lld.inventory.model.Product;
import com.lld.inventory.model.StockChange;
import com.lld.inventory.model.StockItem;
import com.lld.inventory.model.User;
import com.lld.inventory.model.Warehouse;
import com.lld.inventory.store.ProductCatalog;
import com.lld.inventory.store.WarehouseRegistry;

import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;

/**
 * Facade for every inventory use case. Each mutating method follows the same 4 steps:
 *   1. authorise (actor.require)      2. change stock (StockItem, under its lock)
 *   3. write the audit row            4. publish threshold events (outside any lock)
 * No global lock anywhere: two different SKUs (or warehouses) never wait for each other.
 */
public final class InventoryService {
    private final ProductCatalog catalog;
    private final WarehouseRegistry warehouses;
    private final AuditLog audit;
    private final StockEventPublisher events;
    private final Clock clock;

    public InventoryService(ProductCatalog catalog, WarehouseRegistry warehouses, AuditLog audit,
                            StockEventPublisher events, Clock clock) {
        this.catalog = catalog;
        this.warehouses = warehouses;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ setup

    public void addProduct(User actor, Product product) {
        actor.require(Permission.MANAGE_CATALOG);
        catalog.add(product);
    }

    public Warehouse addWarehouse(User actor, String id, String name, String location) {
        actor.require(Permission.MANAGE_WAREHOUSES);
        Warehouse w = new Warehouse(id, name, location);
        warehouses.add(w);
        return w;
    }

    public void configureStock(User actor, String warehouseId, String sku, int reorderLevel, int maxLevel) {
        actor.require(Permission.CONFIGURE_STOCK);
        warehouses.get(warehouseId).stockFor(skuOf(sku)).configure(reorderLevel, maxLevel);
    }

    // ------------------------------------------------------------------ stock movements

    public int receive(User actor, String warehouseId, String sku, int units, String reference) {
        return inbound(actor, Permission.RECEIVE_STOCK, TransactionType.RECEIVE, warehouseId, sku, units, reference);
    }

    /** Customer return of sellable goods. (Damaged returns: receive nothing, or returnStock + writeOff.) */
    public int returnStock(User actor, String warehouseId, String sku, int units, String reference) {
        return inbound(actor, Permission.RETURN_STOCK, TransactionType.RETURN, warehouseId, sku, units, reference);
    }

    public int ship(User actor, String warehouseId, String sku, int units, String reference) {
        return outbound(actor, Permission.SHIP_STOCK, TransactionType.SHIP, warehouseId, sku, units, reference);
    }

    /** Damaged / expired / lost units leave the books, with a reason. */
    public int writeOff(User actor, String warehouseId, String sku, int units, String reason) {
        return outbound(actor, Permission.WRITE_OFF_STOCK, TransactionType.WRITE_OFF, warehouseId, sku, units, reason);
    }

    /** All-or-nothing move between two warehouses (both item locks held, taken in a fixed order). */
    public void transfer(User actor, String fromWarehouseId, String toWarehouseId, String sku, int units,
                         String reference) {
        actor.require(Permission.TRANSFER_STOCK);
        if (fromWarehouseId.equals(toWarehouseId))
            throw new IllegalArgumentException("transfer needs two different warehouses");
        String s = skuOf(sku);
        StockItem from = warehouses.get(fromWarehouseId).require(s);
        StockItem to = warehouses.get(toWarehouseId).stockFor(s);

        StockItem.Transfer t = StockItem.transfer(from, to, units);

        record(TransactionType.TRANSFER_OUT, t.out(), -units, actor, reference);
        record(TransactionType.TRANSFER_IN, t.in(), units, actor, reference);
        publishThresholdEvents(t.out());
        publishThresholdEvents(t.in());
    }

    // ------------------------------------------------------------------ lock-free queries

    public int quantity(String warehouseId, String sku) { return warehouses.get(warehouseId).quantityOf(skuOf(sku)); }

    public int totalQuantity(String sku) {
        String s = skuOf(sku);
        return warehouses.all().stream().mapToInt(w -> w.quantityOf(s)).sum();
    }

    public List<Product> search(Predicate<Product> filter) { return catalog.search(filter); }

    // ------------------------------------------------------------------ internals

    private int inbound(User actor, Permission p, TransactionType type, String warehouseId, String sku,
                        int units, String reference) {
        actor.require(p);
        String s = skuOf(sku);
        StockChange change = warehouses.get(warehouseId).stockFor(s).add(units);
        record(type, change, units, actor, reference);
        publishThresholdEvents(change);
        return change.after();
    }

    private int outbound(User actor, Permission p, TransactionType type, String warehouseId, String sku,
                         int units, String reference) {
        actor.require(p);
        String s = skuOf(sku);
        StockChange change = warehouses.get(warehouseId).require(s).remove(units);  // throws if not enough
        record(type, change, -units, actor, reference);
        publishThresholdEvents(change);
        return change.after();
    }

    /** Validates the SKU against the catalog: stock can only exist for a known product. */
    private String skuOf(String sku) { return catalog.get(sku).sku(); }

    private void record(TransactionType type, StockChange c, int delta, User actor, String reference) {
        audit.append(new StockTransaction(audit.nextId(), clock.instant(), type, c.warehouseId(), c.sku(),
                delta, c.before(), c.after(), actor.id(), reference == null ? "-" : reference));
    }

    private void publishThresholdEvents(StockChange c) {
        if (c.crossedIntoLowStock()) publish(StockEventType.LOW_STOCK, c);
        if (c.ranOut()) publish(StockEventType.OUT_OF_STOCK, c);
        if (c.restocked()) publish(StockEventType.BACK_IN_STOCK, c);
    }

    private void publish(StockEventType type, StockChange c) {
        events.publish(new StockEvent(type, c.warehouseId(), c.sku(), c.after(), c.reorderLevel(), clock.instant()));
    }
}
