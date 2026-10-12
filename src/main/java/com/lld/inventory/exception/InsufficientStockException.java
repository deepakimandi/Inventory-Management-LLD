package com.lld.inventory.exception;

public class InsufficientStockException extends InventoryException {
    public InsufficientStockException(String warehouseId, String sku, int requested, int available) {
        super("Insufficient stock of " + sku + " in " + warehouseId
                + ": requested " + requested + ", available " + available);
    }
}
