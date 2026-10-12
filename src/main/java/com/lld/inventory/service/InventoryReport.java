package com.lld.inventory.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Point-in-time snapshot. Built from lock-free reads, so it never blocks stock movements. */
public record InventoryReport(Instant generatedAt, List<Line> lines) {

    public record Line(String warehouseId, String sku, String productName, int quantity, int reorderLevel, boolean low) { }

    public Map<String, Integer> totalsBySku() {
        Map<String, Integer> totals = new TreeMap<>();
        lines.forEach(l -> totals.merge(l.sku(), l.quantity(), Integer::sum));
        return totals;
    }

    public List<Line> lowStock() { return lines.stream().filter(Line::low).toList(); }

    public String format() {
        StringBuilder sb = new StringBuilder(String.format("  %-5s %-9s %-14s %5s %7s%n", "WH", "SKU", "PRODUCT", "QTY", "REORDER"));
        for (Line l : lines)
            sb.append(String.format("  %-5s %-9s %-14s %5d %7d %s%n", l.warehouseId(), l.sku(), l.productName(),
                    l.quantity(), l.reorderLevel(), l.low() ? "<- LOW" : ""));
        sb.append("  totals: ").append(totalsBySku());
        return sb.toString();
    }
}
