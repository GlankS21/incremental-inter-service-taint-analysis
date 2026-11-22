package com.shop.product.service.impl;

import com.shop.common.event.ReviewReminderDueEvent;
import com.shop.common.event.ReviewReminderItem;
import com.shop.product.config.ReviewReminderProperties;
import com.shop.product.entity.Product;
import com.shop.product.entity.ProductPurchase;
import com.shop.product.entity.ProductStatus;
import com.shop.product.messaging.publisher.ReviewReminderPublisher;
import com.shop.product.repository.ProductPurchaseRepository;
import com.shop.product.repository.ProductRepository;
import com.shop.product.repository.ProductReviewRepository;
import com.shop.product.service.ReviewReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each order is handled in its own transaction: its purchase rows are locked ({@code SKIP LOCKED}, so
 * that two instances never handle the same order), the event is published and confirmed, then the rows
 * are marked processed. A publish failure rolls the transaction back and the order is retried by the
 * next run. If the commit fails after the publish, the next run publishes the same event again: the event
 * id is derived from the order id, and notification-service ignores an event id it has already handled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewReminderServiceImpl implements ReviewReminderService {

    private final ProductPurchaseRepository purchaseRepository;
    private final ProductRepository productRepository;
    private final ProductReviewRepository reviewRepository;
    private final ReviewReminderPublisher publisher;
    private final ReviewReminderProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public int publishDueReminders() {
        Instant now = clock.instant();
        List<UUID> orderIds = purchaseRepository.findOrdersDueForReminder(now.minus(properties.delay()),
                PageRequest.of(0, properties.batchSize()));
        int published = 0;
        for (UUID orderId : orderIds) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> handleOrder(orderId, now)))) {
                    published++;
                }
            } catch (AmqpException ex) {
                // The broker is unreachable: the following orders would fail the same way
                log.warn("Could not publish the review reminder of order {}, retried by the next run: {}",
                        orderId, ex.getMessage());
                break;
            }
        }
        if (published > 0) {
            log.info("Published {} review reminder(s)", published);
        }
        return published;
    }

    /**
     * @return whether a reminder was published (false when every product is reviewed or no longer sold)
     */
    private boolean handleOrder(UUID orderId, Instant now) {
        List<ProductPurchase> rows = purchaseRepository.lockUnremindedByOrderId(orderId);
        if (rows.isEmpty()) {
            // Handled meanwhile by another instance
            return false;
        }
        ProductPurchase first = rows.getFirst();
        UUID userId = first.getUserId();
        List<Long> productIds = rows.stream().map(ProductPurchase::getProductId).distinct().toList();
        Set<Long> reviewed = reviewRepository.findAllOfUserForProducts(userId, productIds).stream()
                .map(review -> review.getProduct().getId())
                .collect(Collectors.toSet());
        Map<Long, Product> active = productRepository.findAllByIdInAndStatus(productIds, ProductStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<ReviewReminderItem> items = productIds.stream()
                .filter(id -> !reviewed.contains(id) && active.containsKey(id))
                .map(active::get)
                .sorted(Comparator.comparing(Product::getName))
                .map(product -> new ReviewReminderItem(product.getId(), product.getName(), product.getSlug()))
                .toList();

        if (!items.isEmpty()) {
            publisher.publish(new ReviewReminderDueEvent(reminderEventId(orderId), now, userId, orderId,
                    first.getOrderNumber(), items));
        } else {
            log.debug("Order {}: every product reviewed or withdrawn, no reminder", orderId);
        }
        purchaseRepository.markReminderProcessed(rows.stream().map(ProductPurchase::getId).toList(), now);
        return !items.isEmpty();
    }

    /**
     * Same order, same event id: a reminder published twice is delivered once.
     */
    static UUID reminderEventId(UUID orderId) {
        return UUID.nameUUIDFromBytes(("review-reminder:" + orderId).getBytes(StandardCharsets.UTF_8));
    }
}
