package com.lld.inventory.store;

import com.lld.inventory.exception.DuplicateEntityException;
import com.lld.inventory.exception.NotFoundException;
import com.lld.inventory.model.Warehouse;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** id -> Warehouse. Lookups by id are O(1); iteration is weakly consistent and never throws CME. */
public final class WarehouseRegistry {
    private final Map<String, Warehouse> warehouses = new ConcurrentHashMap<>();

    public void add(Warehouse w) {
        if (warehouses.putIfAbsent(w.id(), w) != null) throw new DuplicateEntityException("Warehouse", w.id());
    }

    public Warehouse get(String id) {
        Warehouse w = warehouses.get(id);
        if (w == null) throw new NotFoundException("Warehouse", id);
        return w;
    }

    public Collection<Warehouse> all() { return Collections.unmodifiableCollection(warehouses.values()); }
}
