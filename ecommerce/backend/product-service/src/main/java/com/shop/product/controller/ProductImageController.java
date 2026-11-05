package com.shop.product.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.product.config.OpenApiConfig;
import com.shop.product.dto.response.UploadedImageResponse;
import com.shop.product.service.ImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Изображения товаров", description = "Загрузка изображений товаров (ROLE_ADMIN) и их публичное скачивание")
@RestController
@RequestMapping("/api/products/images")
@RequiredArgsConstructor
public class ProductImageController {

    /** Names are random and contents never change, so caches may keep images for a year. */
    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private final ImageStorageService imageStorageService;
    private final MessageSource messageSource;

    @Operation(summary = "Загрузить изображение JPEG, PNG, WebP или GIF (часть multipart \"file\", по умолчанию до 5 МБ)",
            description = "Формат определяется по содержимому файла. Возвращает относительный адрес, "
                    + "который указывается в изображениях товара.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UploadedImageResponse> upload(
            @RequestParam(name = "file", required = false) MultipartFile file) {
        return ApiResponse.success(messageSource.getMessage("image.uploaded", null,
                CommonMessagesAutoConfiguration.DEFAULT_LOCALE), imageStorageService.store(file));
    }

    /**
     * The content type is set explicitly, so the image is returned whatever the {@code Accept} header.
     */
    @Operation(summary = "Скачать загруженное изображение")
    @GetMapping("/{filename}")
    public ResponseEntity<Resource> download(@PathVariable("filename") String filename) {
        ImageStorageService.StoredImage image = imageStorageService.load(filename);
        return ResponseEntity.ok()
                .contentType(image.contentType())
                .contentLength(image.size())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_FOREVER)
                .header("X-Content-Type-Options", "nosniff")
                .body(image.resource());
    }
}
