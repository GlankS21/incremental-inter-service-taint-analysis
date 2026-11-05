package com.shop.product.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.common.security.SecurityUtils;
import com.shop.product.config.OpenApiConfig;
import com.shop.product.dto.request.ProductRequest;
import com.shop.product.dto.request.ProductSearchCriteria;
import com.shop.product.dto.request.ProductStatusRequest;
import com.shop.product.dto.response.ProductResponse;
import com.shop.product.dto.response.ProductSearchResponse;
import com.shop.product.dto.response.ProductSummaryResponse;
import com.shop.product.entity.ProductStatus;
import com.shop.product.service.ProductSearchService;
import com.shop.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "Товары", description = "Просмотр каталога (доступен всем) и управление товарами (ROLE_ADMIN). "
        + "Покупатели видят только товары в статусе ACTIVE, администраторы — также DRAFT и ARCHIVED.")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    /** Price filters fit the {@code NUMERIC(14, 2)} price column; larger values overflow in the query. */
    private static final String PRICE_FILTER_MESSAGE = "{validation.price-filter}";

    private final ProductService productService;
    private final ProductSearchService productSearchService;
    private final MessageSource messageSource;

    @Operation(summary = "Поиск по каталогу",
            description = "Полнотекстовый поиск с учётом морфологии русского языка, синонимов, раскладки "
                    + "клавиатуры и опечаток; ответ содержит исправленный запрос (correctedKeyword) и фасеты "
                    + "(категории, бренды, диапазоны цен). Сортировка по полям relevance, price, name, createdAt, "
                    + "updatedAt, sku, ratingAverage, salesCount. По умолчанию: relevance при наличии keyword, "
                    + "иначе createdAt,desc. Пример: sort=price,asc")
    @GetMapping
    public ApiResponse<ProductSearchResponse> search(
            @Parameter(description = "Поисковый запрос (название, бренд, категория, описание, артикул), "
                    + "не длиннее 100 символов")
            @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "Идентификатор категории; корневая категория включает свои подкатегории")
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "brand", required = false) String brand,
            @Parameter(description = "Цена в рублях: от 0 до 999 999 999 999,99, не больше двух знаков после запятой")
            @RequestParam(name = "minPrice", required = false)
            @DecimalMin(value = "0", message = PRICE_FILTER_MESSAGE)
            @Digits(integer = 12, fraction = 2, message = PRICE_FILTER_MESSAGE) BigDecimal minPrice,
            @Parameter(description = "Цена в рублях: от 0 до 999 999 999 999,99, не больше двух знаков после запятой")
            @RequestParam(name = "maxPrice", required = false)
            @DecimalMin(value = "0", message = PRICE_FILTER_MESSAGE)
            @Digits(integer = 12, fraction = 2, message = PRICE_FILTER_MESSAGE) BigDecimal maxPrice,
            @Parameter(description = "Только для администраторов; для остальных пользователей не учитывается")
            @RequestParam(name = "status", required = false) ProductStatus status,
            @ParameterObject Pageable pageable) {
        ProductSearchCriteria criteria = new ProductSearchCriteria(keyword, categoryId, brand, minPrice, maxPrice, status);
        return ApiResponse.success(productSearchService.search(criteria, pageable, SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Несколько товаров по идентификаторам (не более 100)",
            description = "Товары возвращаются в порядке запроса; неизвестные и недоступные "
                    + "идентификаторы пропускаются.")
    @GetMapping("/by-ids")
    public ApiResponse<List<ProductSummaryResponse>> getByIds(@RequestParam(name = "ids") List<Long> ids) {
        return ApiResponse.success(productService.getProductsByIds(ids, SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Товар по идентификатору")
    @GetMapping("/{productId}")
    public ApiResponse<ProductResponse> get(@PathVariable("productId") Long productId) {
        return ApiResponse.success(productService.getProduct(productId, SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Товар по адресу (slug)")
    @GetMapping("/slug/{slug}")
    public ApiResponse<ProductResponse> getBySlug(@PathVariable("slug") String slug) {
        return ApiResponse.success(productService.getProductBySlug(slug, SecurityUtils.currentUserIsAdmin()));
    }

    @Operation(summary = "Создать товар (в статусе DRAFT, если статус не указан)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ApiResponse.success(message("product.created"), productService.createProduct(request));
    }

    @Operation(summary = "Изменить товар (артикул не меняется; если изображения не переданы, они сохраняются)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{productId}")
    public ApiResponse<ProductResponse> update(@PathVariable("productId") Long productId,
                                               @Valid @RequestBody ProductRequest request) {
        return ApiResponse.success(message("product.updated"), productService.updateProduct(productId, request));
    }

    @Operation(summary = "Опубликовать, снять с публикации или перенести товар в архив",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{productId}/status")
    public ApiResponse<ProductResponse> changeStatus(@PathVariable("productId") Long productId,
                                                     @Valid @RequestBody ProductStatusRequest request) {
        return ApiResponse.success(message("product.status-updated"), productService.changeStatus(productId, request.status()));
    }

    @Operation(summary = "Перенести товар в архив (мягкое удаление: прошлые заказы по-прежнему ссылаются на него)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{productId}")
    public ApiResponse<Void> archive(@PathVariable("productId") Long productId) {
        productService.changeStatus(productId, ProductStatus.ARCHIVED);
        return ApiResponse.message(message("product.archived"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
