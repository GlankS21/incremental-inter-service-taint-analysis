package com.shop.order.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.order.config.OrderProperties;
import com.shop.order.dto.response.OrderStatsResponse;
import com.shop.order.dto.response.OrderStatsResponse.DailyStats;
import com.shop.order.entity.OrderStatus;
import com.shop.order.repository.OrderStatsRepository;
import com.shop.order.repository.OrderStatsRepository.DayCount;
import com.shop.order.repository.OrderStatsRepository.DaySales;
import com.shop.order.repository.OrderStatsRepository.ProductSales;
import com.shop.order.repository.OrderStatsRepository.StatusCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderStatsServiceImplTest {

    /** 00:30 on October 22nd in Moscow (UTC+3). */
    private static final Instant NOW = Instant.parse("2026-10-21T21:30:00Z");

    @Mock
    private OrderStatsRepository statsRepository;

    @Test
    void assemble_fillsEveryDayAndComputesTheTotals() {
        OrderStatsResponse stats = OrderStatsServiceImpl.assemble(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 2),
                "RUB",
                List.of(statusCount("PAID", 2), statusCount("CANCELLED", 1), statusCount("DELIVERED", 1)),
                List.of(dayCount("2026-01-30", 3), dayCount("2026-02-02", 1)),
                List.of(daySales("2026-01-30", 2, "1000.00"), daySales("2026-02-01", 1, "500.01")),
                List.of(productSales(7L, 4, "1200.5")));

        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 1, 30));
        assertThat(stats.currency()).isEqualTo("RUB");
        assertThat(stats.daily()).extracting(DailyStats::date).containsExactly(LocalDate.of(2026, 1, 30),
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2));
        assertThat(stats.daily().get(1)).isEqualTo(new DailyStats(LocalDate.of(2026, 1, 31), 0, 0,
                new BigDecimal("0.00")));
        assertThat(stats.daily().get(2).paidOrders()).isEqualTo(1);
        assertThat(stats.daily().get(3).orders()).isEqualTo(1);
        assertThat(stats.orderCount()).isEqualTo(4);
        assertThat(stats.paidOrderCount()).isEqualTo(3);
        assertThat(stats.cancelledOrderCount()).isEqualTo(1);
        assertThat(stats.revenue()).isEqualByComparingTo("1500.01");
        // 1500.01 / 3 = 500.0033 -> 500.00
        assertThat(stats.averageOrderValue()).isEqualTo(new BigDecimal("500.00"));
        assertThat(stats.ordersByStatus()).containsOnlyKeys(OrderStatus.values());
        assertThat(stats.ordersByStatus().get(OrderStatus.PENDING)).isZero();
        assertThat(stats.ordersByStatus().get(OrderStatus.PAID)).isEqualTo(2);
        assertThat(stats.topProducts()).singleElement().satisfies(product -> {
            assertThat(product.quantity()).isEqualTo(4);
            assertThat(product.revenue()).isEqualTo(new BigDecimal("1200.50"));
        });
    }

    @Test
    void assemble_reportsZeroAverageWithoutSales() {
        OrderStatsResponse stats = OrderStatsServiceImpl.assemble(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1),
                "RUB", List.of(), List.of(), List.of(), List.of());

        assertThat(stats.daily()).hasSize(1);
        assertThat(stats.revenue()).isEqualTo(new BigDecimal("0.00"));
        assertThat(stats.averageOrderValue()).isEqualTo(new BigDecimal("0.00"));
        assertThat(stats.orderCount()).isZero();
    }

    @Test
    void statistics_usesTheLast30DaysOfTheShopTimeZoneByDefault() {
        OrderStatsServiceImpl service = service();

        OrderStatsResponse stats = service.statistics(null, null, 5);

        // 21:30 UTC is already October 22nd in Moscow
        assertThat(stats.to()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(stats.daily()).hasSize(30);
        verify(statsRepository).countCreatedByStatus(Instant.parse("2026-09-22T21:00:00Z"),
                Instant.parse("2026-10-22T21:00:00Z"));
        verify(statsRepository).findTopProducts(Instant.parse("2026-09-22T21:00:00Z"),
                Instant.parse("2026-10-22T21:00:00Z"), 5);
        verify(statsRepository).sumSalesByDay(Instant.parse("2026-09-22T21:00:00Z"),
                Instant.parse("2026-10-22T21:00:00Z"), "Europe/Moscow");
    }

    @Test
    void statistics_refusesInvertedOrTooLongPeriods() {
        OrderStatsServiceImpl service = service();

        assertThatThrownBy(() -> service.statistics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31), 5))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("stats.error.range");
        assertThatThrownBy(() -> service.statistics(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2), 5))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(statsRepository);

        // 366 days is the maximum
        assertThat(service.statistics(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 5).daily()).hasSize(366);
        verify(statsRepository).findTopProducts(eq(Instant.parse("2023-12-31T21:00:00Z")),
                eq(Instant.parse("2024-12-31T21:00:00Z")), anyInt());
    }

    private OrderStatsServiceImpl service() {
        OrderProperties properties = new OrderProperties(null, null, null, null, null, "RUB",
                ZoneId.of("Europe/Moscow"));
        return new OrderStatsServiceImpl(statsRepository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StatusCount statusCount(String status, long orders) {
        return new StatusCount() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public Long getOrders() {
                return orders;
            }
        };
    }

    private static DayCount dayCount(String day, long orders) {
        return new DayCount() {
            @Override
            public String getDay() {
                return day;
            }

            @Override
            public Long getOrders() {
                return orders;
            }
        };
    }

    private static DaySales daySales(String day, long orders, String revenue) {
        return new DaySales() {
            @Override
            public String getDay() {
                return day;
            }

            @Override
            public Long getOrders() {
                return orders;
            }

            @Override
            public BigDecimal getRevenue() {
                return new BigDecimal(revenue);
            }
        };
    }

    private static ProductSales productSales(Long productId, long quantity, String revenue) {
        return new ProductSales() {
            @Override
            public Long getProductId() {
                return productId;
            }

            @Override
            public String getProductName() {
                return "Product " + productId;
            }

            @Override
            public String getSku() {
                return "SKU-" + productId;
            }

            @Override
            public String getThumbnailUrl() {
                return null;
            }

            @Override
            public Long getQuantity() {
                return quantity;
            }

            @Override
            public BigDecimal getRevenue() {
                return new BigDecimal(revenue);
            }
        };
    }
}
