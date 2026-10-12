package com.lld.inventory;

import com.lld.inventory.audit.StockTransaction;
import com.lld.inventory.audit.TransactionType;
import com.lld.inventory.events.StockEvent;
import com.lld.inventory.events.StockEventType;
import com.lld.inventory.exception.AccessDeniedException;
import com.lld.inventory.exception.DuplicateEntityException;
import com.lld.inventory.exception.InsufficientStockException;
import com.lld.inventory.exception.InventoryException;
import com.lld.inventory.exception.NotFoundException;
import com.lld.inventory.model.Product;
import com.lld.inventory.model.ProductCategory;
import com.lld.inventory.model.Role;
import com.lld.inventory.model.User;
import com.lld.inventory.replenishment.FixedQuantityStrategy;
import com.lld.inventory.replenishment.PurchaseOrder;
import com.lld.inventory.replenishment.TopUpToMaxStrategy;
import com.lld.inventory.service.InventoryReport;
import com.lld.inventory.service.InventoryService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lld.inventory.model.ProductCategory.*;
import static com.lld.inventory.store.ProductFilters.*;

/**
 * Dependency-free tests (plain main + assertions) so the project runs with just a JDK.
 * Run: ./run.sh test
 */
public class InventoryTests {

    private static int passed = 0;
    private static int failed = 0;

    static final User ADMIN = new User("admin", "Admin", Role.ADMIN);
    static final User MANAGER = new User("mgr", "Manager", Role.MANAGER);
    static final User STAFF = new User("staff", "Staff", Role.STAFF);
    static final User AUDITOR = new User("audit", "Auditor", Role.AUDITOR);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        run("product builder: validates and normalises", InventoryTests::productBuilder);
        run("receive/ship update quantity and write audit rows with before/after", InventoryTests::receiveAndShip);
        run("over-shipping is rejected and changes nothing", InventoryTests::overShip);
        run("unknown product / warehouse / duplicates are rejected", InventoryTests::notFoundAndDuplicates);
        run("transfer is all-or-nothing and writes OUT + IN rows", InventoryTests::transferAtomic);
        run("transfer with insufficient stock changes neither side", InventoryTests::transferInsufficient);
        run("low-stock alert is edge-triggered (fires once per crossing)", InventoryTests::edgeTriggeredAlert);
        run("out-of-stock and back-in-stock events", InventoryTests::outAndBackInStock);
        run("replenishment strategy is chosen per category", InventoryTests::strategyPerCategory);
        run("one open purchase order per item, even under concurrent triggers", InventoryTests::oneOpenPoPerItem);
        run("receiving a PO closes it; still-low stock reorders again", InventoryTests::receivePo);
        run("role-based access control", InventoryTests::accessControl);
        run("search filters compose", InventoryTests::search);
        run("report totals across warehouses and flags low stock", InventoryTests::report);
        run("a failing listener does not break the stock operation", InventoryTests::listenerIsolation);
        run("CONCURRENCY: 1,000 one-unit shipments on 500 units -> exactly 500 succeed", InventoryTests::noOversell);
        run("CONCURRENCY: mixed receive/ship keeps the audit trail consistent", InventoryTests::auditMatchesQuantity);
        run("CONCURRENCY: opposite transfers A->B and B->A never deadlock, total conserved", InventoryTests::noDeadlock);

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ fixtures

    static InventoryApplication app() {
        InventoryApplication app = new InventoryApplication(CLOCK, Runnable::run,
                Map.of(GROCERY, new TopUpToMaxStrategy()), new FixedQuantityStrategy(50), false);
        InventoryService inv = app.inventory();
        inv.addProduct(ADMIN, Product.builder("LAP-1", "Laptop", ELECTRONICS).price("900").attribute("brand", "Dell").build());
        inv.addProduct(ADMIN, Product.builder("TSH-1", "T-Shirt", CLOTHING).price("20").attribute("size", "M").build());
        inv.addProduct(ADMIN, Product.builder("APL-1", "Apple", GROCERY).price("2.5").build());
        inv.addWarehouse(ADMIN, "A", "Alpha", "City A");
        inv.addWarehouse(ADMIN, "B", "Beta", "City B");
        return app;
    }

    // ------------------------------------------------------------------ tests

    static void productBuilder() {
        Product p = Product.builder(" lap-9 ", "Laptop", ELECTRONICS).price("10.005").attribute("brand", "HP").build();
        check(p.sku().equals("LAP-9"), "sku trimmed + upper-cased");
        check(p.price().toPlainString().equals("10.01"), "price scaled to 2dp");
        check("HP".equals(p.attribute("brand")), "attribute stored");
        expect(IllegalArgumentException.class, () -> Product.builder(" ", "x", OTHER).build());
        expect(IllegalArgumentException.class, () -> Product.builder("S", "x", OTHER).price("-1").build());
    }

    static void receiveAndShip() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        check(inv.receive(STAFF, "A", "LAP-1", 10, "GRN") == 10, "receive returns new qty");
        check(inv.ship(STAFF, "A", "lap-1", 4, "ORD") == 6, "ship returns new qty (sku case-insensitive)");
        List<StockTransaction> trail = app.auditLog().forSku("LAP-1");
        check(trail.size() == 2, "two audit rows");
        StockTransaction ship = trail.get(1);
        check(ship.type() == TransactionType.SHIP && ship.delta() == -4 && ship.before() == 10 && ship.after() == 6
                && ship.actorId().equals("staff") && ship.reference().equals("ORD"), "ship row is complete");
    }

    static void overShip() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.receive(STAFF, "A", "LAP-1", 3, "GRN");
        expect(InsufficientStockException.class, () -> inv.ship(STAFF, "A", "LAP-1", 4, "ORD"));
        expect(NotFoundException.class, () -> inv.ship(STAFF, "B", "LAP-1", 1, "ORD"));   // never stocked in B
        expect(IllegalArgumentException.class, () -> inv.ship(STAFF, "A", "LAP-1", 0, "ORD"));
        check(inv.quantity("A", "LAP-1") == 3, "quantity unchanged");
        check(app.auditLog().forSku("LAP-1").size() == 1, "no audit row for failed ship");
    }

    static void notFoundAndDuplicates() {
        InventoryService inv = app().inventory();
        expect(NotFoundException.class, () -> inv.receive(STAFF, "A", "NOPE", 1, "x"));
        expect(NotFoundException.class, () -> inv.receive(STAFF, "Z", "LAP-1", 1, "x"));
        expect(DuplicateEntityException.class,
                () -> inv.addProduct(ADMIN, Product.builder("LAP-1", "Other", OTHER).build()));
        expect(DuplicateEntityException.class, () -> inv.addWarehouse(ADMIN, "A", "again", "x"));
    }

    static void transferAtomic() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.receive(STAFF, "A", "LAP-1", 10, "GRN");
        inv.transfer(MANAGER, "A", "B", "LAP-1", 7, "TRF-1");
        check(inv.quantity("A", "LAP-1") == 3 && inv.quantity("B", "LAP-1") == 7, "moved 7");
        check(inv.totalQuantity("LAP-1") == 10, "total conserved");
        List<TransactionType> types = app.auditLog().forSku("LAP-1").stream().map(StockTransaction::type).toList();
        check(types.equals(List.of(TransactionType.RECEIVE, TransactionType.TRANSFER_OUT, TransactionType.TRANSFER_IN)),
                "OUT then IN rows, got " + types);
        expect(IllegalArgumentException.class, () -> inv.transfer(MANAGER, "A", "A", "LAP-1", 1, "x"));
    }

    static void transferInsufficient() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.receive(STAFF, "A", "LAP-1", 5, "GRN");
        inv.receive(STAFF, "B", "LAP-1", 2, "GRN");
        expect(InsufficientStockException.class, () -> inv.transfer(MANAGER, "A", "B", "LAP-1", 6, "TRF"));
        check(inv.quantity("A", "LAP-1") == 5 && inv.quantity("B", "LAP-1") == 2, "neither side changed");
    }

    static void edgeTriggeredAlert() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "A", "TSH-1", 10, 100);
        inv.receive(STAFF, "A", "TSH-1", 20, "GRN");
        for (int i = 0; i < 8; i++) inv.ship(STAFF, "A", "TSH-1", 2, "ORD-" + i);   // 20 -> 4
        long low = app.dashboard().alerts().stream().filter(e -> e.type() == StockEventType.LOW_STOCK).count();
        check(low == 1, "exactly one LOW_STOCK, got " + low);
        check(app.replenishment().allOrders().size() == 1, "exactly one PO");
    }

    static void outAndBackInStock() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        Queue<StockEventType> seen = new ConcurrentLinkedQueue<>();
        app.events().subscribe(e -> seen.add(e.type()));
        inv.configureStock(MANAGER, "A", "LAP-1", 2, 10);
        inv.receive(STAFF, "A", "LAP-1", 5, "GRN");       // 0 -> 5 : BACK_IN_STOCK
        inv.ship(STAFF, "A", "LAP-1", 5, "ORD");          // 5 -> 0 : LOW_STOCK + OUT_OF_STOCK
        check(List.copyOf(seen).equals(List.of(StockEventType.BACK_IN_STOCK, StockEventType.LOW_STOCK,
                StockEventType.OUT_OF_STOCK)), "events: " + seen);
    }

    static void strategyPerCategory() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "A", "APL-1", 20, 100);   // GROCERY -> top-up
        inv.configureStock(MANAGER, "A", "LAP-1", 5, 30);     // default -> bulk 50
        inv.receive(STAFF, "A", "APL-1", 30, "GRN");
        inv.receive(STAFF, "A", "LAP-1", 10, "GRN");
        inv.ship(STAFF, "A", "APL-1", 18, "ORD");             // 12 left -> top up 88
        inv.ship(STAFF, "A", "LAP-1", 6, "ORD");              // 4 left  -> 50
        Map<String, Integer> qty = new java.util.HashMap<>();
        app.replenishment().allOrders().forEach(po -> qty.put(po.sku(), po.quantity()));
        check(qty.get("APL-1") == 88, "grocery tops up to max: " + qty);
        check(qty.get("LAP-1") == 50, "electronics bulk 50: " + qty);
    }

    static void oneOpenPoPerItem() throws Exception {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "A", "TSH-1", 10, 100);
        inv.receive(STAFF, "A", "TSH-1", 5, "GRN");   // already low, but no crossing -> no event
        check(app.replenishment().allOrders().isEmpty(), "no PO yet");
        List<Callable<Optional<PurchaseOrder>>> tasks = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            boolean useSweep = i % 2 == 0;
            tasks.add(() -> useSweep ? app.replenishment().sweep().stream().findFirst()
                    : app.replenishment().replenish("A", "TSH-1"));
        }
        runConcurrently(tasks);
        check(app.replenishment().allOrders().size() == 1, "exactly one PO, got " + app.replenishment().allOrders().size());
    }

    static void receivePo() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "A", "LAP-1", 60, 80);   // reorder level above the 50 bulk batch
        inv.receive(STAFF, "A", "LAP-1", 61, "GRN");
        inv.ship(STAFF, "A", "LAP-1", 61, "ORD");             // 0 -> LOW -> PO-1 (50)
        PurchaseOrder po1 = app.replenishment().openOrders().get(0);
        check(app.replenishment().receive(STAFF, po1.id()) == 50, "stock booked in");
        expect(NotFoundException.class, () -> app.replenishment().receive(STAFF, po1.id()));   // twice
        check(app.replenishment().openOrders().size() == 1, "still low (50 <= 60) -> reordered once");
        check(!app.replenishment().openOrders().get(0).id().equals(po1.id()), "it is a new PO");
    }

    static void accessControl() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.receive(STAFF, "A", "LAP-1", 5, "GRN");
        expect(AccessDeniedException.class, () -> inv.ship(AUDITOR, "A", "LAP-1", 1, "x"));
        expect(AccessDeniedException.class, () -> inv.transfer(STAFF, "A", "B", "LAP-1", 1, "x"));
        expect(AccessDeniedException.class, () -> inv.writeOff(STAFF, "A", "LAP-1", 1, "x"));
        expect(AccessDeniedException.class, () -> inv.addWarehouse(MANAGER, "C", "c", "c"));
        expect(AccessDeniedException.class, () -> app.reports().stockReport(STAFF));
        check(inv.quantity("A", "LAP-1") == 5, "nothing changed");
        app.reports().stockReport(AUDITOR);   // allowed
    }

    static void search() {
        InventoryService inv = app().inventory();
        check(inv.search(byCategory(ELECTRONICS)).size() == 1, "by category");
        check(inv.search(priceBetween("1", "25")).size() == 2, "by price");
        check(inv.search(nameContains("shirt").and(attributeEquals("size", "m"))).size() == 1, "composed");
        check(inv.search(byCategory(FURNITURE)).isEmpty(), "none");
    }

    static void report() {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "B", "LAP-1", 5, 20);
        inv.receive(STAFF, "A", "LAP-1", 10, "GRN");
        inv.receive(STAFF, "B", "LAP-1", 3, "GRN");
        InventoryReport r = app.reports().stockReport(AUDITOR);
        check(r.totalsBySku().get("LAP-1") == 13, "total across warehouses");
        check(r.lowStock().size() == 1 && r.lowStock().get(0).warehouseId().equals("B"), "B is low");
    }

    static void listenerIsolation() {
        InventoryApplication app = app();
        app.events().subscribe(e -> { throw new RuntimeException("boom (expected in test)"); });
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "A", "LAP-1", 5, 20);
        inv.receive(STAFF, "A", "LAP-1", 6, "GRN");
        check(inv.ship(STAFF, "A", "LAP-1", 2, "ORD") == 4, "ship still succeeds");
        check(app.replenishment().allOrders().size() == 1, "other listeners still ran");
    }

    static void noOversell() throws Exception {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.configureStock(MANAGER, "A", "TSH-1", 100, 600);
        inv.receive(STAFF, "A", "TSH-1", 500, "GRN");
        AtomicInteger ok = new AtomicInteger(), rejected = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) tasks.add(() -> {
            try { inv.ship(STAFF, "A", "TSH-1", 1, "ORD"); ok.incrementAndGet(); }
            catch (InsufficientStockException e) { rejected.incrementAndGet(); }
            return null;
        });
        runConcurrently(tasks);
        check(ok.get() == 500 && rejected.get() == 500, "ok=" + ok + " rejected=" + rejected);
        check(inv.quantity("A", "TSH-1") == 0, "ends at zero");
        long ships = app.auditLog().forSku("TSH-1").stream().filter(t -> t.type() == TransactionType.SHIP).count();
        check(ships == 500, "500 SHIP rows");
        long lowAlerts = app.dashboard().alerts().stream().filter(e -> e.type() == StockEventType.LOW_STOCK).count();
        check(lowAlerts == 1, "one LOW_STOCK even under contention, got " + lowAlerts);
        check(app.replenishment().allOrders().size() == 1, "one PO");
    }

    static void auditMatchesQuantity() throws Exception {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.receive(STAFF, "A", "LAP-1", 100, "GRN");
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            boolean in = i % 2 == 0;
            tasks.add(() -> {
                try { if (in) inv.receive(STAFF, "A", "LAP-1", 3, "R"); else inv.ship(STAFF, "A", "LAP-1", 5, "S"); }
                catch (InsufficientStockException ignored) { }
                return null;
            });
        }
        runConcurrently(tasks);
        int sumOfDeltas = app.auditLog().forSku("LAP-1").stream().mapToInt(StockTransaction::delta).sum();
        check(sumOfDeltas == inv.quantity("A", "LAP-1"), "sum of audit deltas == quantity");
        check(app.auditLog().forSku("LAP-1").stream().allMatch(t -> t.after() == t.before() + t.delta()), "rows consistent");
    }

    static void noDeadlock() throws Exception {
        InventoryApplication app = app();
        InventoryService inv = app.inventory();
        inv.receive(STAFF, "A", "LAP-1", 1000, "GRN");
        inv.receive(STAFF, "B", "LAP-1", 1000, "GRN");
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            boolean aToB = i % 2 == 0;
            tasks.add(() -> {
                try { inv.transfer(MANAGER, aToB ? "A" : "B", aToB ? "B" : "A", "LAP-1", 1, "T"); }
                catch (InsufficientStockException ignored) { }
                return null;
            });
        }
        boolean finished = runConcurrently(tasks);
        check(finished, "finished within timeout (no deadlock)");
        check(inv.totalQuantity("LAP-1") == 2000, "total conserved: " + inv.totalQuantity("LAP-1"));
    }

    // ------------------------------------------------------------------ mini framework

    /** Starts all tasks together (latch) on 16 threads; returns false on timeout. */
    static <T> boolean runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> t : tasks) futures.add(pool.submit(() -> { start.await(); return t.call(); }));
        start.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(20, TimeUnit.SECONDS);
        if (!done) { pool.shutdownNow(); return false; }
        for (Future<T> f : futures) f.get();   // surface unexpected exceptions
        return true;
    }

    static void run(String name, ThrowingRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "\n      " + t);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void expect(Class<? extends Throwable> type, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    interface ThrowingRunnable { void run() throws Exception; }
}
