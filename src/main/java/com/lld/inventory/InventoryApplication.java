package com.lld.inventory;

import com.lld.inventory.audit.AuditLog;
import com.lld.inventory.audit.InMemoryAuditLog;
import com.lld.inventory.events.DashboardAlertListener;
import com.lld.inventory.events.StockEventPublisher;
import com.lld.inventory.model.ProductCategory;
import com.lld.inventory.replenishment.FixedQuantityStrategy;
import com.lld.inventory.replenishment.ReplenishmentService;
import com.lld.inventory.replenishment.ReplenishmentStrategy;
import com.lld.inventory.replenishment.TopUpToMaxStrategy;
import com.lld.inventory.service.InventoryService;
import com.lld.inventory.service.ReportService;
import com.lld.inventory.store.ProductCatalog;
import com.lld.inventory.store.WarehouseRegistry;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Composition root: the ONE place that picks concrete classes and wires them together.
 * This replaces the resources' InventoryManager Singleton. If a single global instance is required,
 * hold one InventoryApplication in a static holder (see notes §11.1), but the classes stay injectable.
 */
public final class InventoryApplication {
    private final ProductCatalog catalog = new ProductCatalog();
    private final WarehouseRegistry warehouses = new WarehouseRegistry();
    private final AuditLog auditLog = new InMemoryAuditLog();
    private final StockEventPublisher events;
    private final InventoryService inventory;
    private final ReplenishmentService replenishment;
    private final ReportService reports;
    private final DashboardAlertListener dashboard;

    public InventoryApplication(Clock clock, Executor eventExecutor,
                                Map<ProductCategory, ReplenishmentStrategy> strategies,
                                ReplenishmentStrategy defaultStrategy, boolean printAlerts) {
        this.events = new StockEventPublisher(eventExecutor);
        this.inventory = new InventoryService(catalog, warehouses, auditLog, events, clock);
        this.replenishment = new ReplenishmentService(catalog, warehouses, inventory, strategies, defaultStrategy, clock);
        this.reports = new ReportService(catalog, warehouses, auditLog, clock);
        this.dashboard = new DashboardAlertListener(printAlerts);
        events.subscribe(dashboard);         // observer 1: alerts
        events.subscribe(replenishment);     // observer 2: auto purchase orders
    }

    /** Groceries are perishable -> top up just enough; everything else -> bulk batches of 50. */
    public static InventoryApplication withDefaults(Clock clock, boolean printAlerts) {
        return new InventoryApplication(clock, Runnable::run,
                Map.of(ProductCategory.GROCERY, new TopUpToMaxStrategy()),
                new FixedQuantityStrategy(50), printAlerts);
    }

    public ProductCatalog catalog() { return catalog; }
    public WarehouseRegistry warehouses() { return warehouses; }
    public AuditLog auditLog() { return auditLog; }
    public StockEventPublisher events() { return events; }
    public InventoryService inventory() { return inventory; }
    public ReplenishmentService replenishment() { return replenishment; }
    public ReportService reports() { return reports; }
    public DashboardAlertListener dashboard() { return dashboard; }
}
