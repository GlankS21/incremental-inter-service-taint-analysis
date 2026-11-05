package com.shop.product.service.impl;

import com.shop.common.dto.PageResponse;
import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.BusinessException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.product.dto.request.ReviewRequest;
import com.shop.product.dto.response.AdminReviewResponse;
import com.shop.product.dto.response.MyReviewResponse;
import com.shop.product.dto.response.PendingReviewCountResponse;
import com.shop.product.dto.response.PendingReviewResponse;
import com.shop.product.dto.response.ReviewResponse;
import com.shop.product.dto.response.ReviewStatusResponse;
import com.shop.product.dto.response.UserReviewResponse;
import com.shop.product.entity.Product;
import com.shop.product.entity.ProductPurchase;
import com.shop.product.entity.ProductReview;
import com.shop.product.entity.ProductStatus;
import com.shop.product.mapper.ReviewMapper;
import com.shop.product.repository.ProductPurchaseRepository;
import com.shop.product.repository.ProductRepository;
import com.shop.product.repository.ProductReviewRepository;
import com.shop.product.repository.ProductReviewSpecifications;
import com.shop.product.service.ReviewService;
import com.shop.product.util.Paging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Review writes first lock the product row ({@link ProductRepository#findForUpdateById}): writes on
 * the same product are serialised, so the recomputed rating summary always counts every committed
 * review, and two concurrent first reviews of one customer cannot both be inserted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private static final BigDecimal NO_RATING = BigDecimal.ZERO.setScale(1);

    private final ProductReviewRepository reviewRepository;
    private final ProductPurchaseRepository purchaseRepository;
    private final ProductRepository productRepository;
    private final ReviewMapper reviewMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> getProductReviews(Long productId, Pageable pageable, boolean includeHidden) {
        validateSort(pageable.getSort());
        requireVisible(productRepository.findById(productId), productId, includeHidden);
        return PageResponse.from(reviewRepository.findAllByProductId(productId,
                        Paging.withIdTieBreaker(limitPageSize(pageable))),
                reviewMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public MyReviewResponse getMyReview(Long productId, UUID userId, boolean includeHidden) {
        requireVisible(productRepository.findById(productId), productId, includeHidden);
        Instant purchasedAt = purchaseRepository.findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(userId, productId)
                .map(ProductPurchase::getPurchasedAt)
                .orElse(null);
        boolean purchased = purchasedAt != null;
        ReviewResponse review = reviewRepository.findByProductIdAndUserId(productId, userId)
                .map(reviewMapper::toResponse)
                .orElse(null);
        return new MyReviewResponse(purchased, purchased, purchasedAt, review);
    }

    @Override
    @Transactional
    public ReviewResponse saveReview(Long productId, UUID userId, ReviewRequest request, boolean includeHidden) {
        Product product = requireVisible(productRepository.findForUpdateById(productId), productId, includeHidden);
        if (!purchaseRepository.existsByUserIdAndProductId(userId, productId)) {
            throw new BusinessException("review.error.not-purchased");
        }

        ProductReview review = reviewRepository.findByProductIdAndUserId(productId, userId)
                .orElseGet(() -> newReview(product, userId));
        boolean created = review.getId() == null;
        review.setRating(request.rating().shortValue());
        review.setComment(normalizeComment(request.comment()));
        review.setAuthorName(request.authorName().strip());
        // Checked above: only a customer who received the product gets this far
        review.setVerifiedPurchase(true);

        ProductReview saved = reviewRepository.saveAndFlush(review);
        recomputeRating(productId);
        log.info("{} review {} of product {} by user {}", created ? "Created" : "Updated",
                saved.getId(), productId, userId);
        return reviewMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteMyReview(Long productId, UUID userId) {
        productRepository.findForUpdateById(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("review.error.not-found"));
        ProductReview review = reviewRepository.findByProductIdAndUserId(productId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("review.error.not-found"));
        delete(review, productId);
        log.info("User {} deleted review {} of product {}", userId, review.getId(), productId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingReviewResponse> getPendingReviews(UUID userId, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), limitPageSize(pageable).getPageSize());
        Page<ProductPurchase> page = purchaseRepository.findPendingReviews(userId, ProductStatus.ACTIVE, unsorted);
        Map<Long, Product> products = productRepository.findAllByIdIn(
                        page.getContent().stream().map(ProductPurchase::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        // A product deleted since the first query is dropped from the page rather than shown without a name
        List<PendingReviewResponse> content = page.getContent().stream()
                .filter(purchase -> products.containsKey(purchase.getProductId()))
                .map(purchase -> {
                    Product product = products.get(purchase.getProductId());
                    return new PendingReviewResponse(product.getId(), product.getSlug(), product.getName(),
                            product.thumbnailUrl(), purchase.getOrderId(), purchase.getPurchasedAt());
                })
                .toList();
        return PageResponse.<PendingReviewResponse>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PendingReviewCountResponse countPendingReviews(UUID userId, Collection<UUID> orderIds) {
        Set<UUID> orders = distinctIds(orderIds, "review.error.too-many-orders");
        long count = purchaseRepository.findPendingReviews(userId, ProductStatus.ACTIVE, PageRequest.of(0, 1))
                .getTotalElements();
        Map<UUID, Long> byOrder = new LinkedHashMap<>();
        if (!orders.isEmpty()) {
            purchaseRepository.countPendingReviewsByOrder(userId, orders, ProductStatus.ACTIVE)
                    .forEach(row -> byOrder.put(row.getOrderId(), row.getPending()));
        }
        return new PendingReviewCountResponse(count, byOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserReviewResponse> getUserReviews(UUID userId, Pageable pageable) {
        validateSort(pageable.getSort());
        return PageResponse.from(reviewRepository.findAllByUserId(userId,
                        Paging.withIdTieBreaker(limitPageSize(pageable))),
                reviewMapper::toUserResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewStatusResponse> getReviewStatuses(UUID userId, Collection<Long> productIds) {
        Set<Long> ids = distinctIds(productIds, "review.error.too-many-products");
        if (ids.isEmpty()) {
            throw new BadRequestException("review.error.no-products");
        }
        Set<Long> purchased = new HashSet<>(purchaseRepository.findPurchasedProductIds(userId, ids));
        Set<Long> active = productRepository.findAllByIdInAndStatus(ids, ProductStatus.ACTIVE).stream()
                .map(Product::getId)
                .collect(Collectors.toSet());
        Map<Long, Integer> ratings = reviewRepository.findAllOfUserForProducts(userId, ids).stream()
                .collect(Collectors.toMap(review -> review.getProduct().getId(), review -> (int) review.getRating()));
        return ids.stream()
                .map(id -> new ReviewStatusResponse(id, purchased.contains(id),
                        purchased.contains(id) && active.contains(id), ratings.containsKey(id), ratings.get(id)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminReviewResponse> searchReviews(Long productId, Integer rating, Pageable pageable) {
        validateSort(pageable.getSort());
        Specification<ProductReview> specification = Specification.allOf(
                ProductReviewSpecifications.ofProduct(productId),
                ProductReviewSpecifications.hasRating(rating));
        return PageResponse.from(reviewRepository.findAll(specification, Paging.withIdTieBreaker(pageable)),
                reviewMapper::toAdminResponse);
    }

    @Override
    @Transactional
    public void deleteReview(Long reviewId) {
        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ResourceNotFoundException.of("review.error.not-found"));
        Long productId = review.getProduct().getId();
        productRepository.findForUpdateById(productId);
        delete(review, productId);
        log.info("Administrator deleted review {} of product {}", reviewId, productId);
    }

    private void delete(ProductReview review, Long productId) {
        reviewRepository.delete(review);
        reviewRepository.flush();
        recomputeRating(productId);
    }

    /**
     * Mean rating rounded half-up to one decimal, from the reviews as written in this transaction.
     */
    private void recomputeRating(Long productId) {
        long count = reviewRepository.countByProductId(productId);
        Long sum = reviewRepository.sumRatingsByProductId(productId);
        BigDecimal average = count == 0 || sum == null
                ? NO_RATING
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        productRepository.updateRating(productId, average, Math.toIntExact(count));
    }

    private Product requireVisible(Optional<Product> product, Long productId, boolean includeHidden) {
        return product
                .filter(p -> includeHidden || p.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> ResourceNotFoundException.of("review.error.product-not-found", productId));
    }

    private static ProductReview newReview(Product product, UUID userId) {
        ProductReview review = new ProductReview();
        review.setProduct(product);
        review.setUserId(userId);
        return review;
    }

    private static String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return comment.strip();
    }

    /**
     * The ids without nulls and duplicates, in the order given; refused above {@link #MAX_IDS_PER_CALL}.
     */
    private static <T> Set<T> distinctIds(Collection<T> ids, String tooManyKey) {
        Set<T> distinct = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).forEach(distinct::add);
        }
        if (distinct.size() > MAX_IDS_PER_CALL) {
            throw new BadRequestException(tooManyKey, MAX_IDS_PER_CALL);
        }
        return distinct;
    }

    private static Pageable limitPageSize(Pageable pageable) {
        return pageable.getPageSize() <= MAX_PUBLIC_PAGE_SIZE
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), MAX_PUBLIC_PAGE_SIZE, pageable.getSort());
    }

    private void validateSort(Sort sort) {
        sort.forEach(order -> {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("review.error.unsupported-sort",
                        String.join(", ", SORTABLE_PROPERTIES.stream().sorted().toList()));
            }
        });
    }
}
