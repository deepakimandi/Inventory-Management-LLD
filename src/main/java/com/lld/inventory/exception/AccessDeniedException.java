package com.lld.inventory.exception;

import com.lld.inventory.model.Permission;
import com.lld.inventory.model.User;

public class AccessDeniedException extends InventoryException {
    public AccessDeniedException(User user, Permission permission) {
        super(user.id() + " (" + user.role() + ") is not allowed to " + permission);
    }
}
