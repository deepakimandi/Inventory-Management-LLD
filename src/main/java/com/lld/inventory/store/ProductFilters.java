package com.lld.inventory.store;

import com.lld.inventory.model.Product;
import com.lld.inventory.model.ProductCategory;

import java.math.BigDecimal;
import java.util.function.Predicate;

/**
 * Small reusable filters, combined with Predicate.and / or / negate (Specification pattern, the light version).
 *   catalog.search(byCategory(ELECTRONICS).and(priceBetween("100", "2000")))
 */
public final class ProductFilters {
    private ProductFilters() { }

    public static Predicate<Product> byCategory(ProductCategory c) { return p -> p.category() == c; }

    public static Predicate<Product> nameContains(String text) {
        String t = text.toLowerCase();
        return p -> p.name().toLowerCase().contains(t);
    }

    public static Predicate<Product> priceBetween(String min, String max) {
        BigDecimal lo = new BigDecimal(min), hi = new BigDecimal(max);
        return p -> p.price().compareTo(lo) >= 0 && p.price().compareTo(hi) <= 0;
    }

    public static Predicate<Product> attributeEquals(String key, String value) {
        return p -> value.equalsIgnoreCase(p.attribute(key));
    }
}
