package com.shop.product.repository;

import com.shop.product.entity.ProductStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Catalogue filters of a keyword search, resolved by the service: {@code categoryIds} already contains the
 * sub-categories of a root category, {@code status} is ACTIVE for customers. A {@code null} field is not
 * filtered on.
 */
public record SearchFilters(
        ProductStatus status,
        List<Long> categoryIds,
        String brand,
        BigDecimal minPrice,
        BigDecimal maxPrice
) {

    public SearchFilters {
        categoryIds = categoryIds == null ? null : List.copyOf(categoryIds);
        brand = brand == null || brand.isBlank() ? null : brand.trim().toLowerCase(Locale.ROOT);
    }

    /** What customers can see: active products, nothing else filtered. */
    public static SearchFilters publicCatalog() {
        return new SearchFilters(ProductStatus.ACTIVE, null, null, null, null);
    }

    public SearchFilters withoutCategories() {
        return new SearchFilters(status, null, brand, minPrice, maxPrice);
    }

    public SearchFilters withoutBrand() {
        return new SearchFilters(status, categoryIds, null, minPrice, maxPrice);
    }

    public SearchFilters withoutPrice() {
        return new SearchFilters(status, categoryIds, brand, null, null);
    }
}
