package com.shop.order.dto.response;

import com.shop.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Dashboard of a period of calendar days (inclusive). Order counts are about the orders created in
 * the period; sales (revenue, paid orders, best sellers) about the orders paid in the period and
 * not cancelled since.
 *
 * @param ordersByStatus     current status of the orders created in the period, every status present
 * @param averageOrderValue  revenue / paidOrderCount, zero without sales
 * @param daily              one entry per day of the period, days without activity included
 */
public record OrderStatsResponse(
        LocalDate from,
        LocalDate to,
        String currency,
        BigDecimal revenue,
        long orderCount,
        long paidOrderCount,
        long cancelledOrderCount,
        BigDecimal averageOrderValue,
        Map<OrderStatus, Long> ordersByStatus,
        List<DailyStats> daily,
        List<TopProduct> topProducts
) {

    /**
     * @param orders     orders created that day
     * @param paidOrders orders paid that day (and not cancelled since)
     * @param revenue    total amount of the paid orders
     */
    public record DailyStats(LocalDate date, long orders, long paidOrders, BigDecimal revenue) {
    }

    /**
     * @param revenue sum of the line totals (before any coupon discount of the order)
     */
    public record TopProduct(Long productId, String productName, String sku, String thumbnailUrl, long quantity,
                             BigDecimal revenue) {
    }
}
