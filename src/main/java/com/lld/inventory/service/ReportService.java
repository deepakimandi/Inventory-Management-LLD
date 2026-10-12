package com.lld.inventory.service;

import com.lld.inventory.audit.AuditLog;
import com.lld.inventory.audit.StockTransaction;
import com.lld.inventory.model.Permission;
import com.lld.inventory.model.StockItem;
import com.lld.inventory.model.User;
import com.lld.inventory.model.Warehouse;
import com.lld.inventory.store.ProductCatalog;
import com.lld.inventory.store.WarehouseRegistry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read side: stock status and movement history. Separate from InventoryService (SRP). */
public final class ReportService {
    private final ProductCatalog catalog;
    private final WarehouseRegistry warehouses;
    private final AuditLog audit;
    private final Clock clock;

    public ReportService(ProductCatalog catalog, WarehouseRegistry warehouses, AuditLog audit, Clock clock) {
        this.catalog = catalog;
        this.warehouses = warehouses;
        this.audit = audit;
        this.clock = clock;
    }

    public InventoryReport stockReport(User actor) {
        actor.require(Permission.VIEW_REPORTS);
        List<InventoryReport.Line> lines = new ArrayList<>();
        for (Warehouse w : warehouses.all())
            for (StockItem i : w.items()) {
                int qty = i.quantity();                      // read once, so qty and 'low' agree
                lines.add(new InventoryReport.Line(w.id(), i.sku(), catalog.get(i.sku()).name(),
                        qty, i.reorderLevel(), qty <= i.reorderLevel()));
            }
        lines.sort(Comparator.comparing(InventoryReport.Line::warehouseId).thenComparing(InventoryReport.Line::sku));
        return new InventoryReport(clock.instant(), lines);
    }

    public List<StockTransaction> movements(User actor, String sku) {
        actor.require(Permission.VIEW_REPORTS);
        return audit.forSku(catalog.get(sku).sku());
    }

    public List<StockTransaction> allMovements(User actor) {
        actor.require(Permission.VIEW_REPORTS);
        return audit.all();
    }
}
