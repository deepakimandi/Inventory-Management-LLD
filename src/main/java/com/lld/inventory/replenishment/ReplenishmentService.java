package com.lld.inventory.replenishment;

import com.lld.inventory.events.StockEvent;
import com.lld.inventory.events.StockEventListener;
import com.lld.inventory.events.StockEventType;
import com.lld.inventory.exception.NotFoundException;
import com.lld.inventory.model.ProductCategory;
import com.lld.inventory.model.StockItem;
import com.lld.inventory.model.User;
import com.lld.inventory.model.Warehouse;
import com.lld.inventory.service.InventoryService;
import com.lld.inventory.store.ProductCatalog;
import com.lld.inventory.store.WarehouseRegistry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observer of LOW_STOCK events + owner of purchase orders.
 *
 * Idempotency: at most ONE open purchase order per (warehouse, sku). Low-stock signals can arrive
 * from several places at once (the event, the periodic sweep, a manual trigger); putIfAbsent on
 * {@code openByItem} lets exactly one of them create the order.
 */
public final class ReplenishmentService implements StockEventListener {
    private final ProductCatalog catalog;
    private final WarehouseRegistry warehouses;
    private final InventoryService inventory;
    private final Map<ProductCategory, ReplenishmentStrategy> strategies;
    private final ReplenishmentStrategy defaultStrategy;
    private final Clock clock;

    private final Map<String, PurchaseOrder> openByItem = new ConcurrentHashMap<>();   // "WH/SKU" -> open PO
    private final Map<String, PurchaseOrder> openById = new ConcurrentHashMap<>();
    private final Queue<PurchaseOrder> history = new ConcurrentLinkedQueue<>();
    private final AtomicLong ids = new AtomicLong();

    public ReplenishmentService(ProductCatalog catalog, WarehouseRegistry warehouses, InventoryService inventory,
                                Map<ProductCategory, ReplenishmentStrategy> strategies,
                                ReplenishmentStrategy defaultStrategy, Clock clock) {
        this.catalog = catalog;
        this.warehouses = warehouses;
        this.inventory = inventory;
        this.strategies = strategies.isEmpty() ? new EnumMap<>(ProductCategory.class) : new EnumMap<>(strategies);
        this.defaultStrategy = defaultStrategy;
        this.clock = clock;
    }

    @Override public void onEvent(StockEvent e) {
        if (e.type() == StockEventType.LOW_STOCK) replenish(e.warehouseId(), e.sku());
    }

    /** Safe to call from any number of threads: creates at most one open PO per item. */
    public Optional<PurchaseOrder> replenish(String warehouseId, String sku) {
        StockItem item = warehouses.get(warehouseId).require(sku);
        if (!item.isLow()) return Optional.empty();                 // re-check: the signal may be stale
        if (openByItem.containsKey(item.key())) return Optional.empty();   // fast path, already ordered

        ReplenishmentStrategy strategy = strategies.getOrDefault(catalog.get(sku).category(), defaultStrategy);
        int qty = strategy.reorderQuantity(item);
        if (qty <= 0) return Optional.empty();

        PurchaseOrder po = new PurchaseOrder("PO-" + ids.incrementAndGet(), warehouseId, sku, qty,
                strategy.name(), clock.instant());
        if (openByItem.putIfAbsent(item.key(), po) != null) return Optional.empty();   // another thread won
        openById.put(po.id(), po);
        history.add(po);
        System.out.printf("  [replenish] %s placed: %d x %s for %s (%s)%n",
                po.id(), qty, sku, warehouseId, strategy.name());
        return Optional.of(po);
    }

    /** Supplier delivered: book the stock in, close the PO, and reorder again if demand is still ahead. */
    public int receive(User actor, String purchaseOrderId) {
        PurchaseOrder po = openById.remove(purchaseOrderId);          // atomic: a PO can be received only once
        if (po == null) throw new NotFoundException("Open purchase order", purchaseOrderId);
        int newQty;
        try {
            newQty = inventory.receive(actor, po.warehouseId(), po.sku(), po.quantity(), po.id());
        } catch (RuntimeException e) {
            openById.put(po.id(), po);                               // undo, so it can be retried
            throw e;
        }
        openByItem.remove(po.itemKey(), po);
        replenish(po.warehouseId(), po.sku());                        // still low? (no new LOW event would fire)
        return newQty;
    }

    /** Safety net, run on a schedule (e.g. ScheduledExecutorService every 15 min): catches anything missed. */
    public List<PurchaseOrder> sweep() {
        List<PurchaseOrder> placed = new ArrayList<>();
        for (Warehouse w : warehouses.all())
            for (StockItem item : w.items())
                if (item.isLow()) replenish(w.id(), item.sku()).ifPresent(placed::add);
        return placed;
    }

    public List<PurchaseOrder> openOrders() { return List.copyOf(openById.values()); }
    public List<PurchaseOrder> allOrders() { return List.copyOf(history); }
}
