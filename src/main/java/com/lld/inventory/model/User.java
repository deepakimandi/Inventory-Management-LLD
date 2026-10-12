package com.lld.inventory.model;

import com.lld.inventory.exception.AccessDeniedException;

import java.util.Objects;

public record User(String id, String name, Role role) {
    public User {
        Objects.requireNonNull(id);
        Objects.requireNonNull(role);
    }

    public void require(Permission p) {
        if (!role.allows(p)) throw new AccessDeniedException(this, p);
    }
}
