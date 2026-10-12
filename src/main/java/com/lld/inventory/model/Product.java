package com.lld.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Catalog entry: WHAT the product is. Immutable, shared by every warehouse.
 * It deliberately has NO quantity and NO threshold: those are per-warehouse facts and live in {@link StockItem}.
 * Category-specific details (brand, size, expiry...) go in {@code attributes} instead of one subclass per category.
 */
public final class Product {
    private final String sku;
    private final String name;
    private final ProductCategory category;
    private final BigDecimal price;
    private final Map<String, String> attributes;

    private Product(Builder b) {
        this.sku = b.sku;
        this.name = b.name;
        this.category = b.category;
        this.price = b.price;
        this.attributes = Map.copyOf(b.attributes);
    }

    public static Builder builder(String sku, String name, ProductCategory category) {
        return new Builder(sku, name, category);
    }

    public String sku() { return sku; }
    public String name() { return name; }
    public ProductCategory category() { return category; }
    public BigDecimal price() { return price; }
    public Map<String, String> attributes() { return attributes; }
    public String attribute(String key) { return attributes.get(key); }

    @Override public boolean equals(Object o) { return o instanceof Product p && p.sku.equals(sku); }
    @Override public int hashCode() { return sku.hashCode(); }
    @Override public String toString() { return sku + " (" + name + ", " + category + ", " + price + ")"; }

    /** Builder: 3 required fields in the constructor, the rest optional and chainable. */
    public static final class Builder {
        private final String sku;
        private final String name;
        private final ProductCategory category;
        private BigDecimal price = BigDecimal.ZERO;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String sku, String name, ProductCategory category) {
            this.sku = requireText(sku, "sku").trim().toUpperCase();
            this.name = requireText(name, "name").trim();
            this.category = Objects.requireNonNull(category, "category");
        }

        public Builder price(String amount) { return price(new BigDecimal(amount)); }

        public Builder price(BigDecimal amount) {
            if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("price must be >= 0");
            this.price = amount.setScale(2, RoundingMode.HALF_UP);
            return this;
        }

        public Builder attribute(String key, String value) {
            attributes.put(requireText(key, "attribute key"), Objects.requireNonNull(value, "attribute value"));
            return this;
        }

        public Product build() { return new Product(this); }

        private static String requireText(String s, String field) {
            if (s == null || s.isBlank()) throw new IllegalArgumentException(field + " is blank");
            return s;
        }
    }
}
