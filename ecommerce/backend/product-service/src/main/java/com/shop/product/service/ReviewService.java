package com.shop.product.service;

import com.shop.common.dto.PageResponse;
import com.shop.product.dto.request.ReviewRequest;
import com.shop.product.dto.response.AdminReviewResponse;
import com.shop.product.dto.response.MyReviewResponse;
import com.shop.product.dto.response.PendingReviewCountResponse;
import com.shop.product.dto.response.PendingReviewResponse;
import com.shop.product.dto.response.ReviewResponse;
import com.shop.product.dto.response.ReviewStatusResponse;
import com.shop.product.dto.response.UserReviewResponse;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Product reviews. Only a customer who received the product in a DELIVERED order may review it, once
 * (later writes edit the same review). Every write recomputes the rating summary of the product in
 * the same transaction.
 * <p>
 * Customer operations take {@code includeHidden} like {@link ProductService}: for anybody but an
 * administrator a product that is not ACTIVE behaves as not found.
 */
public interface ReviewService {

    int MAX_PUBLIC_PAGE_SIZE = 50;
    /** Products per {@code /api/reviews/status} call, orders per {@code /api/reviews/pending/count} call. */
    int MAX_IDS_PER_CALL = 50;
    Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "updatedAt", "rating");

    PageResponse<ReviewResponse> getProductReviews(Long productId, Pageable pageable, boolean includeHidden);

    MyReviewResponse getMyReview(Long productId, UUID userId, boolean includeHidden);

    /**
     * Creates the caller's review of the product, or replaces its rating, comment and author name.
     *
     * @throws com.shop.common.exception.BusinessException when the caller has not received the product
     */
    ReviewResponse saveReview(Long productId, UUID userId, ReviewRequest request, boolean includeHidden);

    void deleteMyReview(Long productId, UUID userId);

    /**
     * Products the customer received and has not reviewed (still in the catalogue), newest delivery first.
     * The order is fixed: a sort in {@code pageable} is ignored.
     */
    PageResponse<PendingReviewResponse> getPendingReviews(UUID userId, Pageable pageable);

    /**
     * @param orderIds orders to count separately (at most {@link #MAX_IDS_PER_CALL}); may be empty
     */
    PendingReviewCountResponse countPendingReviews(UUID userId, Collection<UUID> orderIds);

    /**
     * The reviews the customer wrote, whatever the state of their product.
     */
    PageResponse<UserReviewResponse> getUserReviews(UUID userId, Pageable pageable);

    /**
     * Review state of each given product for the customer, in the order asked (duplicates removed).
     *
     * @param productIds 1 to {@link #MAX_IDS_PER_CALL} ids; unknown products are reported as not purchased
     */
    List<ReviewStatusResponse> getReviewStatuses(UUID userId, Collection<Long> productIds);

    PageResponse<AdminReviewResponse> searchReviews(Long productId, Integer rating, Pageable pageable);

    void deleteReview(Long reviewId);
}
