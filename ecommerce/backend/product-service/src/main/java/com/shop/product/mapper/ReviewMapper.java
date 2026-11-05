package com.shop.product.mapper;

import com.shop.product.dto.response.AdminReviewResponse;
import com.shop.product.dto.response.ReviewResponse;
import com.shop.product.dto.response.ReviewVisibility;
import com.shop.product.dto.response.UserReviewResponse;
import com.shop.product.entity.Product;
import com.shop.product.entity.ProductReview;
import com.shop.product.entity.ProductStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ReviewMapper {

    @Mapping(target = "productId", source = "product.id")
    ReviewResponse toResponse(ProductReview review);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productName", source = "product.name")
    @Mapping(target = "productSlug", source = "product.slug")
    AdminReviewResponse toAdminResponse(ProductReview review);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productName", source = "product.name")
    @Mapping(target = "productSlug", source = "product.slug")
    @Mapping(target = "productImageUrl", expression = "java(review.getProduct().thumbnailUrl())")
    @Mapping(target = "status", expression = "java(visibilityOf(review.getProduct()))")
    UserReviewResponse toUserResponse(ProductReview review);

    default ReviewVisibility visibilityOf(Product product) {
        return product.getStatus() == ProductStatus.ACTIVE ? ReviewVisibility.PUBLISHED : ReviewVisibility.HIDDEN;
    }
}
