package com.lld.inventory.model;

import java.util.EnumSet;
import java.util.Set;

import static com.lld.inventory.model.Permission.*;

/** Role-based access control: the permission table lives in one place. */
public enum Role {
    ADMIN(EnumSet.allOf(Permission.class)),
    MANAGER(EnumSet.of(CONFIGURE_STOCK, RECEIVE_STOCK, SHIP_STOCK, RETURN_STOCK,
            TRANSFER_STOCK, WRITE_OFF_STOCK, VIEW_REPORTS)),
    STAFF(EnumSet.of(RECEIVE_STOCK, SHIP_STOCK, RETURN_STOCK)),
    AUDITOR(EnumSet.of(VIEW_REPORTS));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) { this.permissions = permissions; }

    public boolean allows(Permission p) { return permissions.contains(p); }
}
