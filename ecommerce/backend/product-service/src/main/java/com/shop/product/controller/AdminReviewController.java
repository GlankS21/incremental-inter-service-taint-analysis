package com.shop.product.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.common.dto.PageResponse;
import com.shop.product.config.OpenApiConfig;
import com.shop.product.dto.response.AdminReviewResponse;
import com.shop.product.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Модерация отзывов", description = "Все отзывы и удаление любого отзыва (только для администраторов)")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/reviews/admin")
@RequiredArgsConstructor
public class AdminReviewController {

    private final ReviewService reviewService;
    private final MessageSource messageSource;

    @Operation(summary = "Поиск отзывов, сначала новые",
            description = "Сортировка по полям createdAt, updatedAt, rating.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping
    public ApiResponse<PageResponse<AdminReviewResponse>> search(
            @RequestParam(name = "productId", required = false) Long productId,
            @RequestParam(name = "rating", required = false) @Min(1) @Max(5) Integer rating,
            @ParameterObject
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(reviewService.searchReviews(productId, rating, pageable));
    }

    @Operation(summary = "Удалить любой отзыв (рейтинг товара пересчитывается)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @DeleteMapping("/{reviewId}")
    public ApiResponse<Void> delete(@PathVariable("reviewId") Long reviewId) {
        reviewService.deleteReview(reviewId);
        return ApiResponse.message(messageSource.getMessage("review.deleted", null,
                CommonMessagesAutoConfiguration.DEFAULT_LOCALE));
    }
}
