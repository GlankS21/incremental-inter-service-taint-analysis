package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Error body of API v3, e.g. {@code {"type":"error","code":"invalid_request","description":"...","parameter":"amount"}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaError(String type, String id, String code, String description, String parameter) {
}
