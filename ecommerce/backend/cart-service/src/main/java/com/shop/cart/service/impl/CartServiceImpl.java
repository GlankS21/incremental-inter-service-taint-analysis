package com.shop.cart.service.impl;

import com.shop.cart.client.ProductCatalog;
import com.shop.cart.client.ProductSummary;
import com.shop.cart.config.CartProperties;
import com.shop.cart.dto.request.AddCartItemRequest;
import com.shop.cart.dto.response.CartItemResponse;
import com.shop.cart.dto.response.CartItemStatus;
import com.shop.cart.dto.response.CartResponse;
import com.shop.cart.entity.Cart;
import com.shop.cart.entity.CartItem;
import com.shop.cart.repository.CartRepository;
import com.shop.cart.service.CartService;
import com.shop.cart.util.RussianPlural;
import com.shop.common.event.OrderCreatedEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.BusinessException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.common.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Calls to product-service and inventory-service are made outside database transactions, so no
 * connection is held while waiting for them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    static final String TOO_MANY_PRODUCTS = "cart.error.too-many-products";
    static final String QUANTITY_LIMIT = "cart.error.quantity-limit";
    static final String PRODUCT_NOT_AVAILABLE = "cart.error.product-not-available";
    static final String OUT_OF_STOCK = "cart.error.out-of-stock";
    static final String INSUFFICIENT_STOCK = "cart.error.insufficient-stock";
    static final String NOT_IN_CART = "cart.error.not-in-cart";

    private final CartRepository cartRepository;
    private final ProductCatalog productCatalog;
    private final CartProperties properties;
    private final TransactionTemplate transactionTemplate;

    @Override
    public CartResponse getCart(UUID userId) {
        return cartRepository.findByUserId(userId)
                .filter(cart -> !cart.getItems().isEmpty())
                .map(cart -> price(cart.getItems()))
                .orElseGet(CartResponse::empty);
    }

    @Override
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        ProductSummary product = requireSellable(request.productId());
        Optional<Integer> available = availabilityOf(product.id());
        transactionTemplate.executeWithoutResult(status -> {
            Cart cart = lockOrCreateCart(userId);
            CartItem item = cart.findItem(product.id()).orElse(null);
            if (item == null) {
                if (cart.getItems().size() >= properties.maxItems()) {
                    throw new BusinessException(TOO_MANY_PRODUCTS, properties.maxItems(),
                            RussianPlural.category(properties.maxItems()));
                }
                item = new CartItem();
                item.setProductId(product.id());
                cart.addItem(item);
            }
            // Saturating sum: an int overflow would wrap around to a negative quantity and pass the limit
            int quantity = (int) Math.min((long) item.getQuantity() + request.quantity(), Integer.MAX_VALUE);
            checkQuantity(product, quantity, available);
            item.setQuantity(quantity);
            copySnapshot(product, item);
            cartRepository.saveAndFlush(cart);
            log.info("Cart of {}: product {} now x{}", userId, product.id(), quantity);
        });
        return getCart(userId);
    }

    @Override
    public CartResponse updateItem(UUID userId, Long productId, int quantity) {
        ProductSummary product = requireSellable(productId);
        Optional<Integer> available = availabilityOf(productId);
        transactionTemplate.executeWithoutResult(status -> {
            Cart cart = cartRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> notInCart(productId));
            CartItem item = cart.findItem(productId).orElseThrow(() -> notInCart(productId));
            checkQuantity(product, quantity, available);
            item.setQuantity(quantity);
            copySnapshot(product, item);
            cartRepository.saveAndFlush(cart);
        });
        return getCart(userId);
    }

    @Override
    public CartResponse removeItem(UUID userId, Long productId) {
        transactionTemplate.executeWithoutResult(status -> {
            Cart cart = cartRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> notInCart(productId));
            if (!cart.removeItem(productId)) {
                throw notInCart(productId);
            }
            cartRepository.saveAndFlush(cart);
        });
        return getCart(userId);
    }

    @Override
    @Transactional
    public void clearCart(UUID userId) {
        cartRepository.findByUserIdForUpdate(userId).ifPresent(cart -> cart.getItems().clear());
    }

    /**
     * Subtracts the ordered quantities: units the customer kept in the cart beyond the order stay there.
     * A redelivered event is recognised by its id and changes nothing.
     */
    @Override
    @Transactional
    public void removeOrderedProducts(OrderCreatedEvent event) {
        if (cartRepository.markEventProcessed(event.eventId()) == 0) {
            log.info("Order {}: event {} already applied to the cart", event.orderNumber(), event.eventId());
            return;
        }
        Map<Long, Integer> ordered = event.items().stream()
                .collect(Collectors.toMap(OrderItemPayload::productId, OrderItemPayload::quantity, Integer::sum));
        cartRepository.findByUserIdForUpdate(event.userId()).ifPresentOrElse(
                cart -> {
                    int changed = 0;
                    for (Iterator<CartItem> lines = cart.getItems().iterator(); lines.hasNext(); ) {
                        CartItem item = lines.next();
                        Integer quantity = ordered.get(item.getProductId());
                        if (quantity == null) {
                            continue;
                        }
                        changed++;
                        if (item.getQuantity() > quantity) {
                            item.setQuantity(item.getQuantity() - quantity);
                        } else {
                            lines.remove();
                        }
                    }
                    log.info("Order {}: {} of {} ordered product(s) taken out of the cart of {}",
                            event.orderNumber(), changed, ordered.size(), event.userId());
                },
                () -> log.debug("Order {}: customer {} has no cart", event.orderNumber(), event.userId()));
    }

    /**
     * Prices the lines with the current catalogue and stock; falls back to the saved data when a
     * service cannot be reached.
     */
    private CartResponse price(List<CartItem> items) {
        List<Long> productIds = items.stream().map(CartItem::getProductId).toList();
        Map<Long, ProductSummary> products;
        try {
            products = productCatalog.findSellableProducts(productIds);
        } catch (ServiceUnavailableException ex) {
            log.warn("Cart priced with saved prices: {}", ex.getMessage());
            products = null;
        }
        Optional<Map<Long, Integer>> availability = productCatalog.findAvailability(productIds);

        List<CartItemResponse> lines = new ArrayList<>(items.size());
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQuantity = 0;
        boolean checkoutReady = true;
        for (CartItem item : items) {
            ProductSummary product = products == null ? null : products.get(item.getProductId());
            Integer available = availability.map(stock -> stock.getOrDefault(item.getProductId(), 0)).orElse(null);
            CartItemStatus status = status(item, products, product, available);
            BigDecimal unitPrice = product != null ? product.price() : item.getUnitPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            lines.add(new CartItemResponse(
                    item.getProductId(),
                    item.getSku(),
                    product != null ? product.name() : item.getProductName(),
                    product != null ? product.thumbnailUrl() : item.getThumbnailUrl(),
                    unitPrice,
                    product != null ? product.currency() : item.getCurrency(),
                    item.getQuantity(),
                    lineTotal,
                    unitPrice.compareTo(item.getUnitPrice()) != 0,
                    available,
                    status,
                    item.getCreatedAt()));
            totalQuantity += item.getQuantity();
            if (status != CartItemStatus.UNAVAILABLE) {
                subtotal = subtotal.add(lineTotal);
            }
            checkoutReady &= !status.blocksCheckout();
        }
        String currency = lines.isEmpty() ? null : lines.getFirst().currency();
        return new CartResponse(lines, totalQuantity, subtotal, currency, checkoutReady, products != null);
    }

    private static CartItemStatus status(CartItem item, Map<Long, ProductSummary> products, ProductSummary product,
                                         Integer available) {
        if (products == null) {
            return CartItemStatus.UNKNOWN;
        }
        if (product == null) {
            return CartItemStatus.UNAVAILABLE;
        }
        if (available == null) {
            return CartItemStatus.UNKNOWN;
        }
        if (available <= 0) {
            return CartItemStatus.OUT_OF_STOCK;
        }
        return available < item.getQuantity() ? CartItemStatus.INSUFFICIENT_STOCK : CartItemStatus.AVAILABLE;
    }

    private ProductSummary requireSellable(Long productId) {
        ProductSummary product = productCatalog.findSellableProducts(List.of(productId)).get(productId);
        if (product == null) {
            throw new BusinessException(PRODUCT_NOT_AVAILABLE);
        }
        return product;
    }

    private Optional<Integer> availabilityOf(Long productId) {
        return productCatalog.findAvailability(List.of(productId))
                .map(stock -> stock.getOrDefault(productId, 0));
    }

    /**
     * When the stock is unknown the quantity is accepted: the order saga makes the final check.
     */
    private void checkQuantity(ProductSummary product, int quantity, Optional<Integer> available) {
        if (quantity > properties.maxQuantityPerItem()) {
            throw new BadRequestException(QUANTITY_LIMIT, properties.maxQuantityPerItem(),
                    RussianPlural.category(properties.maxQuantityPerItem()));
        }
        available.ifPresent(units -> {
            if (units <= 0) {
                throw new BusinessException(OUT_OF_STOCK, product.name());
            }
            if (quantity > units) {
                throw new BusinessException(INSUFFICIENT_STOCK, product.name(), units, RussianPlural.category(units));
            }
        });
    }

    private static void copySnapshot(ProductSummary product, CartItem item) {
        item.setSku(product.sku());
        item.setProductName(product.name());
        item.setThumbnailUrl(product.thumbnailUrl());
        item.setUnitPrice(product.price());
        item.setCurrency(product.currency());
    }

    /**
     * The locked cart of the customer, created first when they have none yet.
     */
    private Cart lockOrCreateCart(UUID userId) {
        return cartRepository.findByUserIdForUpdate(userId).orElseGet(() -> {
            cartRepository.insertIfAbsent(UUID.randomUUID(), userId);
            return cartRepository.findByUserIdForUpdate(userId).orElseThrow();
        });
    }

    private static ResourceNotFoundException notInCart(Long productId) {
        return ResourceNotFoundException.of(NOT_IN_CART);
    }
}
