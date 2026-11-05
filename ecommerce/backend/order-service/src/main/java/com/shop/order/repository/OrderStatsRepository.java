package com.shop.order.repository;

import com.shop.order.entity.Order;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregates of the administration dashboard, computed by the database. Periods are half-open
 * {@code [from, to)}; days are calendar days in the time zone {@code zone} (an IANA id).
 * <p>
 * Sales are the orders paid in the period that were not cancelled afterwards (PAID, SHIPPED or
 * DELIVERED).
 */
public interface OrderStatsRepository extends Repository<Order, UUID> {

    @Query(value = """
            select o.status as status, count(*) as orders
            from orders o
            where o.created_at >= :from and o.created_at < :to
            group by o.status
            """, nativeQuery = true)
    List<StatusCount> countCreatedByStatus(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select to_char(o.created_at at time zone :zone, 'YYYY-MM-DD') as day, count(*) as orders
            from orders o
            where o.created_at >= :from and o.created_at < :to
            group by 1
            """, nativeQuery = true)
    List<DayCount> countCreatedByDay(@Param("from") Instant from, @Param("to") Instant to,
                                     @Param("zone") String zone);

    @Query(value = """
            select to_char(o.paid_at at time zone :zone, 'YYYY-MM-DD') as day,
                   count(*) as orders,
                   sum(o.total_amount) as revenue
            from orders o
            where o.paid_at >= :from and o.paid_at < :to
              and o.status in ('PAID', 'SHIPPED', 'DELIVERED')
            group by 1
            """, nativeQuery = true)
    List<DaySales> sumSalesByDay(@Param("from") Instant from, @Param("to") Instant to, @Param("zone") String zone);

    /**
     * Best sellers by quantity, then revenue (sum of the line totals). Name, SKU and thumbnail are
     * those of the most recently paid order.
     */
    @Query(value = """
            select i.product_id as productId,
                   (array_agg(i.product_name order by o.paid_at desc, i.id desc))[1] as productName,
                   (array_agg(i.sku order by o.paid_at desc, i.id desc))[1] as sku,
                   (array_agg(i.thumbnail_url order by o.paid_at desc, i.id desc))[1] as thumbnailUrl,
                   sum(i.quantity) as quantity,
                   sum(i.line_total) as revenue
            from order_items i
                     join orders o on o.id = i.order_id
            where o.paid_at >= :from and o.paid_at < :to
              and o.status in ('PAID', 'SHIPPED', 'DELIVERED')
            group by i.product_id
            order by quantity desc, revenue desc, i.product_id
            limit :top
            """, nativeQuery = true)
    List<ProductSales> findTopProducts(@Param("from") Instant from, @Param("to") Instant to, @Param("top") int top);

    interface StatusCount {
        String getStatus();

        Long getOrders();
    }

    /**
     * @return {@code getDay()} in ISO format ({@code yyyy-MM-dd})
     */
    interface DayCount {
        String getDay();

        Long getOrders();
    }

    interface DaySales {
        String getDay();

        Long getOrders();

        BigDecimal getRevenue();
    }

    interface ProductSales {
        Long getProductId();

        String getProductName();

        String getSku();

        String getThumbnailUrl();

        Long getQuantity();

        BigDecimal getRevenue();
    }
}
