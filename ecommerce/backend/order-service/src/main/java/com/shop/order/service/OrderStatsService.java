package com.shop.order.service;

import com.shop.order.dto.response.OrderStatsResponse;

import java.time.LocalDate;

/**
 * Figures of the administration dashboard.
 */
public interface OrderStatsService {

    /**
     * @param from first day (inclusive), {@code null} for 29 days before {@code to}
     * @param to   last day (inclusive), {@code null} for today; days are calendar days in
     *             {@code app.order.time-zone}
     * @param top  number of best-selling products
     * @throws com.shop.common.exception.BadRequestException when {@code from} is after {@code to} or the
     *                                                       period is longer than 366 days
     */
    OrderStatsResponse statistics(LocalDate from, LocalDate to, int top);
}
