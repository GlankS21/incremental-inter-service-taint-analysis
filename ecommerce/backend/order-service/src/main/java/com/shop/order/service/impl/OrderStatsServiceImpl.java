package com.shop.order.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.order.config.OrderProperties;
import com.shop.order.dto.response.OrderStatsResponse;
import com.shop.order.dto.response.OrderStatsResponse.DailyStats;
import com.shop.order.dto.response.OrderStatsResponse.TopProduct;
import com.shop.order.entity.OrderStatus;
import com.shop.order.repository.OrderStatsRepository;
import com.shop.order.repository.OrderStatsRepository.DayCount;
import com.shop.order.repository.OrderStatsRepository.DaySales;
import com.shop.order.repository.OrderStatsRepository.ProductSales;
import com.shop.order.repository.OrderStatsRepository.StatusCount;
import com.shop.order.service.OrderStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderStatsServiceImpl implements OrderStatsService {

    static final int DEFAULT_PERIOD_DAYS = 30;
    static final int MAX_PERIOD_DAYS = 366;

    private final OrderStatsRepository statsRepository;
    private final OrderProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public OrderStatsResponse statistics(LocalDate from, LocalDate to, int top) {
        ZoneId zone = properties.timeZone();
        LocalDate lastDay = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate firstDay = from != null ? from : lastDay.minusDays(DEFAULT_PERIOD_DAYS - 1L);
        if (firstDay.isAfter(lastDay) || ChronoUnit.DAYS.between(firstDay, lastDay) + 1 > MAX_PERIOD_DAYS) {
            throw new BadRequestException("stats.error.range", MAX_PERIOD_DAYS);
        }
        Instant start = firstDay.atStartOfDay(zone).toInstant();
        Instant end = lastDay.plusDays(1).atStartOfDay(zone).toInstant();
        return assemble(firstDay, lastDay, properties.currency(),
                statsRepository.countCreatedByStatus(start, end),
                statsRepository.countCreatedByDay(start, end, zone.getId()),
                statsRepository.sumSalesByDay(start, end, zone.getId()),
                statsRepository.findTopProducts(start, end, top));
    }

    /**
     * Builds the response from the aggregates: every status and every day of the period is present,
     * with zeros when nothing happened; totals are the sums of the daily figures.
     */
    static OrderStatsResponse assemble(LocalDate from, LocalDate to, String currency, List<StatusCount> statusCounts,
                                       List<DayCount> createdByDay, List<DaySales> salesByDay,
                                       List<ProductSales> topProducts) {
        Map<OrderStatus, Long> ordersByStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            ordersByStatus.put(status, 0L);
        }
        statusCounts.forEach(row -> ordersByStatus.merge(OrderStatus.valueOf(row.getStatus()), row.getOrders(),
                Long::sum));

        Map<LocalDate, Long> created = new HashMap<>();
        createdByDay.forEach(row -> created.put(LocalDate.parse(row.getDay()), row.getOrders()));
        Map<LocalDate, DaySales> sales = new HashMap<>();
        salesByDay.forEach(row -> sales.put(LocalDate.parse(row.getDay()), row));

        List<DailyStats> daily = new ArrayList<>();
        long paidOrderCount = 0;
        BigDecimal revenue = zero();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DaySales daySales = sales.get(day);
            long paidOrders = daySales == null ? 0 : daySales.getOrders();
            BigDecimal dayRevenue = daySales == null || daySales.getRevenue() == null
                    ? zero() : daySales.getRevenue().setScale(2, RoundingMode.HALF_UP);
            daily.add(new DailyStats(day, created.getOrDefault(day, 0L), paidOrders, dayRevenue));
            paidOrderCount += paidOrders;
            revenue = revenue.add(dayRevenue);
        }

        long orderCount = ordersByStatus.values().stream().mapToLong(Long::longValue).sum();
        BigDecimal averageOrderValue = paidOrderCount == 0 ? zero()
                : revenue.divide(BigDecimal.valueOf(paidOrderCount), 2, RoundingMode.HALF_UP);
        List<TopProduct> products = topProducts.stream()
                .map(row -> new TopProduct(row.getProductId(), row.getProductName(), row.getSku(),
                        row.getThumbnailUrl(), row.getQuantity(), row.getRevenue().setScale(2, RoundingMode.HALF_UP)))
                .toList();
        return new OrderStatsResponse(from, to, currency, revenue, orderCount, paidOrderCount,
                ordersByStatus.get(OrderStatus.CANCELLED), averageOrderValue,
                Collections.unmodifiableMap(ordersByStatus), List.copyOf(daily), products);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }
}
