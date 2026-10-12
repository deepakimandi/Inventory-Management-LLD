package com.lld.inventory.events;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Concrete observer: keeps the alert feed a dashboard (or an on-call e-mail) would show. Ignores BACK_IN_STOCK. */
public final class DashboardAlertListener implements StockEventListener {
    private final Queue<StockEvent> alerts = new ConcurrentLinkedQueue<>();
    private final boolean print;

    public DashboardAlertListener(boolean print) { this.print = print; }

    @Override public void onEvent(StockEvent e) {
        if (e.type() == StockEventType.BACK_IN_STOCK) return;
        alerts.add(e);
        if (print) {
            String level = switch (e.type()) {
                case OUT_OF_STOCK -> "CRITICAL";
                case LOW_STOCK -> "WARNING ";
                default -> "INFO    ";
            };
            System.out.printf("  [alert %s] %s %s/%s qty=%d (reorder level %d)%n",
                    level, e.type(), e.warehouseId(), e.sku(), e.quantity(), e.reorderLevel());
        }
    }

    public List<StockEvent> alerts() { return List.copyOf(alerts); }
}
