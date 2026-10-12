package com.lld.inventory.store;

import com.lld.inventory.exception.DuplicateEntityException;
import com.lld.inventory.exception.NotFoundException;
import com.lld.inventory.model.Product;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** SKU -> Product. One catalog for the whole business; warehouses only refer to SKUs. */
public final class ProductCatalog {
    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public void add(Product product) {
        if (products.putIfAbsent(product.sku(), product) != null)      // atomic check-and-insert
            throw new DuplicateEntityException("Product", product.sku());
    }

    public Optional<Product> find(String sku) { return Optional.ofNullable(products.get(normalise(sku))); }

    public Product get(String sku) { return find(sku).orElseThrow(() -> new NotFoundException("Product", sku)); }

    /** Search = any composition of predicates (see ProductFilters). */
    public List<Product> search(Predicate<Product> filter) {
        return products.values().stream().filter(filter).sorted(Comparator.comparing(Product::sku)).toList();
    }

    public List<Product> all() { return search(p -> true); }

    public static String normalise(String sku) { return sku == null ? null : sku.trim().toUpperCase(); }
}
