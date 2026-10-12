package com.lld.inventory.exception;

/** Base type for every business-rule failure in the system. */
public class InventoryException extends RuntimeException {
    public InventoryException(String message) { super(message); }
}
