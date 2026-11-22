package com.shop.product.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.common.dto.PageResponse;
import com.shop.common.security.SecurityUtils;
import com.shop.product.config.OpenApiConfig;
import com.shop.product.dto.request.ReviewRequest;
import com.shop.product.dto.response.MyReviewResponse;
import com.shop.product.dto.response.PendingReviewCountResponse;
import com.shop.product.dto.response.PendingReviewResponse;
import com.shop.product.dto.response.ReviewResponse;
import com.shop.product.dto.response.ReviewStatusResponse;
import com.shop.product.dto.response.UserReviewResponse;
import com.shop.product.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The public listing lives under {@code /api/products} (public GET at the gateway); the caller's own
 * review under {@code /api/reviews}, which always requires an access token.
 */
@Tag(name = "Отзывы", description = "Отзывы о товарах. Читать может любой; написать один отзыв о товаре могут "
        + "только покупатели, получившие его в доставленном заказе.")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final MessageSource messageSource;

    @Operation(summary = "Отзывы о товаре, сначала новые",
            description = "Размер страницы — не более " + ReviewService.MAX_PUBLIC_PAGE_SIZE
                    + ". Сортировка по полям createdAt, updatedAt, rating.")
    @GetMapping("/api/products/{productId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> list(
            @PathVariable("productId") Long productId,
            @ParameterObject
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(reviewService.getProductReviews(productId, pageable,
                SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Может ли пользователь оставить отзыв о товаре, и его собственный отзыв",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/api/reviews/products/{productId}/mine")
    public ApiResponse<MyReviewResponse> mine(@PathVariable("productId") Long productId) {
        return ApiResponse.success(reviewService.getMyReview(productId, SecurityUtils.currentUserId(),
                SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Создать или изменить свой отзыв (только для покупателей, иначе 422)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PutMapping("/api/reviews/products/{productId}")
    public ApiResponse<ReviewResponse> save(@PathVariable("productId") Long productId,
                                            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(message("review.saved"), reviewService.saveReview(productId,
                SecurityUtils.currentUserId(), request, SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Удалить свой отзыв",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @DeleteMapping("/api/reviews/products/{productId}")
    public ApiResponse<Void> delete(@PathVariable("productId") Long productId) {
        reviewService.deleteMyReview(productId, SecurityUtils.currentUserId());
        return ApiResponse.message(message("review.deleted"));
    }

    @Operation(summary = "Полученные товары, которые я ещё не оценил, сначала недавно доставленные",
            description = "Товары, снятые с продажи, не показываются. Порядок фиксирован, размер страницы — не более "
                    + ReviewService.MAX_PUBLIC_PAGE_SIZE + ".",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/api/reviews/pending")
    public ApiResponse<PageResponse<PendingReviewResponse>> pending(
            @ParameterObject @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.success(reviewService.getPendingReviews(SecurityUtils.currentUserId(), pageable));
    }

    @Operation(summary = "Сколько товаров ждут моего отзыва (всего и по заказам)",
            description = "orderIds — необязательный список заказов (не более " + ReviewService.MAX_IDS_PER_CALL
                    + "), для которых число считается отдельно.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/api/reviews/pending/count")
    public ApiResponse<PendingReviewCountResponse> pendingCount(
            @RequestParam(name = "orderIds", required = false) List<UUID> orderIds) {
        return ApiResponse.success(reviewService.countPendingReviews(SecurityUtils.currentUserId(),
                orderIds == null ? List.of() : orderIds));
    }

    @Operation(summary = "Мои отзывы с товарами, сначала изменённые недавно",
            description = "Размер страницы — не более " + ReviewService.MAX_PUBLIC_PAGE_SIZE
                    + ". Сортировка по полям createdAt, updatedAt, rating.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/api/reviews/mine")
    public ApiResponse<PageResponse<UserReviewResponse>> myReviews(
            @ParameterObject
            @PageableDefault(size = 10, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(reviewService.getUserReviews(SecurityUtils.currentUserId(), pageable));
    }

    @Operation(summary = "Куплен ли и оценён ли каждый из товаров (одним запросом)",
            description = "productIds — от 1 до " + ReviewService.MAX_IDS_PER_CALL + " идентификаторов через запятую.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/api/reviews/status")
    public ApiResponse<List<ReviewStatusResponse>> statuses(@RequestParam("productIds") List<Long> productIds) {
        return ApiResponse.success(reviewService.getReviewStatuses(SecurityUtils.currentUserId(), productIds));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
