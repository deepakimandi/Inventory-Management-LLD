package com.lld.inventory.exception;

/** Unknown product, warehouse, stock item or purchase order. */
public class NotFoundException extends InventoryException {
    public NotFoundException(String what, String id) { super(what + " not found: " + id); }
}
