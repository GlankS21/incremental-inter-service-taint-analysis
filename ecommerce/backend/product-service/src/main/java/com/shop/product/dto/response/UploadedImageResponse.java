package com.shop.product.dto.response;

/**
 * @param url         relative URL served by product-service, usable as {@code ProductImageRequest.url}
 * @param contentType detected from the file content
 * @param size        in bytes
 */
public record UploadedImageResponse(
        String url,
        String contentType,
        long size
) {
}
