package com.shop.product.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Deterministic paging: the requested order is completed by the id (newest first), so rows that tie on
 * the sort columns (the sample catalogue shares one creation date) keep their order from one page to
 * the next instead of being repeated or skipped.
 */
public final class Paging {

    private static final String ID = "id";

    public static Sort withIdTieBreaker(Sort sort) {
        return sort.getOrderFor(ID) != null ? sort : sort.and(Sort.by(Sort.Order.desc(ID)));
    }

    public static Pageable withIdTieBreaker(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                withIdTieBreaker(pageable.getSort()));
    }

    private Paging() {
    }
}
