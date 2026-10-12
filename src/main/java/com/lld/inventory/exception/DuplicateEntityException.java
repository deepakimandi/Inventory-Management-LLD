package com.lld.inventory.exception;

public class DuplicateEntityException extends InventoryException {
    public DuplicateEntityException(String what, String id) { super(what + " already exists: " + id); }
}
