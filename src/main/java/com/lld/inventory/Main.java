package com.lld.inventory;

import com.lld.inventory.audit.StockTransaction;
import com.lld.inventory.exception.InventoryException;
import com.lld.inventory.model.Product;
import com.lld.inventory.model.Role;
import com.lld.inventory.model.User;
import com.lld.inventory.replenishment.PurchaseOrder;
import com.lld.inventory.service.InventoryService;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lld.inventory.model.ProductCategory.*;
import static com.lld.inventory.store.ProductFilters.*;

/** End-to-end demo. Run: ./run.sh */
public class Main {
    public static void main(String[] args) throws Exception {
        InventoryApplication app = InventoryApplication.withDefaults(Clock.systemUTC(), true);
        InventoryService inv = app.inventory();

        User admin = new User("u-admin", "Asha", Role.ADMIN);
        User manager = new User("u-mgr", "Ravi", Role.MANAGER);
        User staff = new User("u-staff", "Kiran", Role.STAFF);
        User auditor = new User("u-audit", "Meera", Role.AUDITOR);

        section("1. Setup: catalog, warehouses, reorder levels");
        inv.addProduct(admin, Product.builder("LAP-001", "Laptop", ELECTRONICS).price("899.99")
                .attribute("brand", "Dell").attribute("warrantyMonths", "24").build());
        inv.addProduct(admin, Product.builder("TSH-001", "T-Shirt", CLOTHING).price("19.50")
                .attribute("size", "M").attribute("color", "Blue").build());
        inv.addProduct(admin, Product.builder("APL-001", "Apple (kg)", GROCERY).price("2.40")
                .attribute("perishable", "true").build());
        inv.addWarehouse(admin, "BLR", "Bengaluru DC", "Hoskote");
        inv.addWarehouse(admin, "HYD", "Hyderabad DC", "Shamshabad");
        inv.configureStock(manager, "BLR", "LAP-001", 10, 60);
        inv.configureStock(manager, "BLR", "APL-001", 20, 100);
        inv.configureStock(manager, "BLR", "TSH-001", 30, 250);
        inv.configureStock(manager, "HYD", "LAP-001", 5, 30);
        System.out.println("  catalog: " + app.catalog().all());

        section("2. Receive and ship");
        inv.receive(staff, "BLR", "LAP-001", 40, "GRN-1001");
        inv.receive(staff, "BLR", "TSH-001", 200, "GRN-1002");
        inv.receive(staff, "BLR", "APL-001", 80, "GRN-1003");
        inv.receive(staff, "HYD", "LAP-001", 8, "GRN-2001");
        inv.ship(staff, "BLR", "LAP-001", 25, "ORD-1");
        System.out.println("  BLR laptops: " + inv.quantity("BLR", "LAP-001"));

        section("3. Crossing the reorder level -> alert + automatic purchase order (Observer + Strategy)");
        inv.ship(staff, "BLR", "LAP-001", 6, "ORD-2");     // 15 -> 9  (crosses 10)
        inv.ship(staff, "BLR", "LAP-001", 2, "ORD-3");     // 9 -> 7   (no new alert: edge-triggered)
        inv.ship(staff, "BLR", "APL-001", 65, "ORD-4");    // 80 -> 15 (grocery: top-up strategy)
        System.out.println("  open POs: " + app.replenishment().openOrders().size());

        section("4. Supplier delivers -> PO received, stock recovers");
        for (PurchaseOrder po : app.replenishment().openOrders())
            System.out.println("  received " + po.id() + " -> new qty " + app.replenishment().receive(staff, po.id()));

        section("5. Transfer, write-off, return");
        inv.transfer(manager, "BLR", "HYD", "LAP-001", 10, "TRF-1");
        inv.writeOff(manager, "BLR", "TSH-001", 3, "damaged in handling");
        inv.returnStock(staff, "BLR", "TSH-001", 1, "RMA-77");
        System.out.println("  laptops BLR=" + inv.quantity("BLR", "LAP-001") + " HYD=" + inv.quantity("HYD", "LAP-001")
                + " total=" + inv.totalQuantity("LAP-001"));

        section("6. Illegal actions are rejected");
        attempt(() -> inv.ship(staff, "HYD", "LAP-001", 999, "ORD-X"));
        attempt(() -> inv.transfer(staff, "BLR", "HYD", "LAP-001", 1, "TRF-X"));
        attempt(() -> inv.ship(auditor, "BLR", "LAP-001", 1, "ORD-Y"));
        attempt(() -> inv.receive(staff, "BLR", "NOPE-1", 1, "GRN-X"));

        section("7. Search");
        System.out.println("  electronics under 1000: " + inv.search(byCategory(ELECTRONICS).and(priceBetween("0", "1000"))));
        System.out.println("  name contains 'shirt' : " + inv.search(nameContains("shirt")));

        section("8. Concurrency: 16 threads race to ship T-shirts one unit at a time, 180 more orders than stock");
        int before = inv.quantity("BLR", "TSH-001");
        AtomicInteger ok = new AtomicInteger(), rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < before + 180; i++) {
            int n = i;
            pool.submit(() -> {
                try { inv.ship(staff, "BLR", "TSH-001", 1, "RUSH-" + n); ok.incrementAndGet(); }
                catch (InventoryException e) { rejected.incrementAndGet(); }
            });
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.printf("  had %d, shipped %d, rejected %d, left %d (never negative, never oversold)%n",
                before, ok.get(), rejected.get(), inv.quantity("BLR", "TSH-001"));

        section("9. Report (auditor)");
        System.out.println(app.reports().stockReport(auditor).format());

        section("10. Audit trail for LAP-001");
        List<StockTransaction> trail = app.reports().movements(auditor, "LAP-001");
        trail.forEach(t -> System.out.println("  " + t));
    }

    private static void attempt(Runnable r) {
        try { r.run(); System.out.println("  !! unexpectedly allowed"); }
        catch (InventoryException e) { System.out.println("  rejected: " + e.getMessage()); }
    }

    private static void section(String title) { System.out.println("\n== " + title); }
}
