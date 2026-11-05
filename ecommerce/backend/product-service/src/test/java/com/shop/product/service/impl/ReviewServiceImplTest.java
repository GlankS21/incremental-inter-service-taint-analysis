package com.shop.product.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.BusinessException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.product.dto.request.ReviewRequest;
import com.shop.product.dto.response.MyReviewResponse;
import com.shop.product.dto.response.ReviewResponse;
import com.shop.product.entity.Product;
import com.shop.product.entity.ProductPurchase;
import com.shop.product.entity.ProductReview;
import com.shop.product.entity.ProductStatus;
import com.shop.product.mapper.ReviewMapperImpl;
import com.shop.product.repository.ProductPurchaseRepository;
import com.shop.product.repository.ProductRepository;
import com.shop.product.repository.ProductReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    private static final Long PRODUCT_ID = 7L;
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant DELIVERED_AT = Instant.parse("2026-10-20T09:00:00Z");

    @Mock
    private ProductReviewRepository reviewRepository;
    @Mock
    private ProductPurchaseRepository purchaseRepository;
    @Mock
    private ProductRepository productRepository;

    private ReviewServiceImpl service;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new ReviewServiceImpl(reviewRepository, purchaseRepository, productRepository, new ReviewMapperImpl());
        product = new Product();
        product.setId(PRODUCT_ID);
        product.setName("iPhone 15");
        product.setSlug("iphone-15");
        product.setStatus(ProductStatus.ACTIVE);
    }

    @Test
    void saveReview_rejectsCustomerWhoDidNotReceiveTheProduct() {
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(purchaseRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.saveReview(PRODUCT_ID, USER_ID, request(5, "Супер", "Анна"), false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("review.error.not-purchased");
        verify(reviewRepository, never()).saveAndFlush(any());
        verify(productRepository, never()).updateRating(anyLong(), any(), anyInt());
    }

    @Test
    void saveReview_hiddenProductIsNotFoundForCustomers() {
        product.setStatus(ProductStatus.DRAFT);
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.saveReview(PRODUCT_ID, USER_ID, request(5, null, "Анна"), false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("review.error.product-not-found");
    }

    @Test
    void saveReview_createsNormalisedReviewAndRecomputesRating() {
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(purchaseRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).thenReturn(true);
        when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(ProductReview.class))).thenAnswer(inv -> {
            ProductReview review = inv.getArgument(0);
            review.setId(100L);
            return review;
        });
        // Existing reviews 5 and 4 plus the new 4: 13 / 3 = 4.333 -> 4.3
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(3L);
        when(reviewRepository.sumRatingsByProductId(PRODUCT_ID)).thenReturn(13L);

        ReviewResponse response = service.saveReview(PRODUCT_ID, USER_ID, request(4, "   ", "  Анна  "), false);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.rating()).isEqualTo(4);
        assertThat(response.comment()).isNull();
        assertThat(response.authorName()).isEqualTo("Анна");
        ArgumentCaptor<ProductReview> saved = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getProduct()).isSameAs(product);
        verify(productRepository).updateRating(PRODUCT_ID, new BigDecimal("4.3"), 3);
    }

    @Test
    void saveReview_updatesTheExistingReviewAndRoundsHalfUp() {
        ProductReview existing = review(55L, 2, "Так себе");
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(purchaseRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).thenReturn(true);
        when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(existing));
        when(reviewRepository.saveAndFlush(existing)).thenReturn(existing);
        // 9 / 2 = 4.5 exactly; 17 / 4 = 4.25 -> 4.3 (half-up)
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(4L);
        when(reviewRepository.sumRatingsByProductId(PRODUCT_ID)).thenReturn(17L);

        ReviewResponse response = service.saveReview(PRODUCT_ID, USER_ID, request(5, " Передумала, отлично ", "Анна"), false);

        assertThat(response.id()).isEqualTo(55L);
        assertThat(existing.getRating()).isEqualTo((short) 5);
        assertThat(existing.getComment()).isEqualTo("Передумала, отлично");
        verify(productRepository).updateRating(PRODUCT_ID, new BigDecimal("4.3"), 4);
    }

    @Test
    void getMyReview_reportsPurchaseAndExistingReview() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        ProductPurchase purchase = new ProductPurchase();
        purchase.setPurchasedAt(DELIVERED_AT);
        when(purchaseRepository.findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(USER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(purchase));
        when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID))
                .thenReturn(Optional.of(review(55L, 4, null)));

        MyReviewResponse mine = service.getMyReview(PRODUCT_ID, USER_ID, false);

        assertThat(mine.purchased()).isTrue();
        assertThat(mine.canReview()).isTrue();
        assertThat(mine.purchasedAt()).isEqualTo(DELIVERED_AT);
        assertThat(mine.review().id()).isEqualTo(55L);
    }

    @Test
    void getMyReview_nonBuyerCannotReview() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(purchaseRepository.findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(USER_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());
        when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());

        MyReviewResponse mine = service.getMyReview(PRODUCT_ID, USER_ID, false);

        assertThat(mine.purchased()).isFalse();
        assertThat(mine.canReview()).isFalse();
        assertThat(mine.review()).isNull();
    }

    @Test
    void deleteMyReview_removesItAndResetsRatingWhenLast() {
        ProductReview existing = review(55L, 4, null);
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(existing));
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(0L);
        when(reviewRepository.sumRatingsByProductId(PRODUCT_ID)).thenReturn(null);

        service.deleteMyReview(PRODUCT_ID, USER_ID);

        verify(reviewRepository).delete(existing);
        verify(productRepository).updateRating(PRODUCT_ID, new BigDecimal("0.0"), 0);
    }

    @Test
    void deleteMyReview_withoutReviewIsNotFound() {
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMyReview(PRODUCT_ID, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("review.error.not-found");
        verify(reviewRepository, never()).delete(any(ProductReview.class));
    }

    @Test
    void deleteReview_byAdministratorLocksProductAndRecomputes() {
        ProductReview existing = review(55L, 1, "Плохо");
        when(reviewRepository.findById(55L)).thenReturn(Optional.of(existing));
        when(productRepository.findForUpdateById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(2L);
        when(reviewRepository.sumRatingsByProductId(PRODUCT_ID)).thenReturn(9L);

        service.deleteReview(55L);

        verify(productRepository).findForUpdateById(PRODUCT_ID);
        verify(reviewRepository).delete(existing);
        verify(productRepository).updateRating(PRODUCT_ID, new BigDecimal("4.5"), 2);
    }

    @Test
    void deleteReview_unknownIdIsNotFound() {
        when(reviewRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteReview(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getProductReviews_capsPageSizeAndRejectsUnknownSort() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(reviewRepository.findAllByProductId(eq(PRODUCT_ID), any(Pageable.class)))
                .thenAnswer(inv -> Page.empty(inv.getArgument(1)));

        service.getProductReviews(PRODUCT_ID, PageRequest.of(0, 500, Sort.by("createdAt")), false);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findAllByProductId(eq(PRODUCT_ID), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);

        assertThatThrownBy(() -> service.getProductReviews(PRODUCT_ID, PageRequest.of(0, 10, Sort.by("userId")), false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("review.error.unsupported-sort");
    }

    @Test
    void reviewListingsBreakTiesById() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(reviewRepository.findAllByProductId(eq(PRODUCT_ID), any(Pageable.class)))
                .thenAnswer(inv -> Page.empty(inv.getArgument(1)));
        when(reviewRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> Page.empty(inv.getArgument(1)));
        Pageable requested = PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Sort expected = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

        service.getProductReviews(PRODUCT_ID, requested, false);
        service.searchReviews(null, null, requested);

        ArgumentCaptor<Pageable> publicPage = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findAllByProductId(eq(PRODUCT_ID), publicPage.capture());
        assertThat(publicPage.getValue().getSort()).isEqualTo(expected);
        assertThat(publicPage.getValue().getPageNumber()).isEqualTo(1);
        ArgumentCaptor<Pageable> adminPage = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findAll(any(Specification.class), adminPage.capture());
        assertThat(adminPage.getValue().getSort()).isEqualTo(expected);
    }

    private ProductReview review(Long id, int rating, String comment) {
        ProductReview review = new ProductReview();
        review.setId(id);
        review.setProduct(product);
        review.setUserId(USER_ID);
        review.setAuthorName("Анна");
        review.setRating((short) rating);
        review.setComment(comment);
        return review;
    }

    private static ReviewRequest request(int rating, String comment, String authorName) {
        return new ReviewRequest(rating, comment, authorName);
    }
}
