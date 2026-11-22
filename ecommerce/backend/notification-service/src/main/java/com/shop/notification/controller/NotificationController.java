package com.shop.notification.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.common.dto.PageResponse;
import com.shop.common.security.SecurityUtils;
import com.shop.notification.dto.request.NotificationPreferencesRequest;
import com.shop.notification.dto.response.NotificationPreferencesResponse;
import com.shop.notification.dto.response.NotificationResponse;
import com.shop.notification.service.NotificationPreferenceService;
import com.shop.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Уведомления", description = "Письма и уведомления в личном кабинете текущего покупателя, настройки уведомлений")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;
    private final MessageSource messageSource;

    @Operation(summary = "Мои уведомления, сначала новые")
    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> mine(
            @ParameterObject
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(notificationService.listOf(SecurityUtils.currentUserId(), pageable));
    }

    @Operation(summary = "Мои настройки уведомлений")
    @GetMapping("/preferences")
    public ApiResponse<NotificationPreferencesResponse> preferences() {
        return ApiResponse.success(preferenceService.get(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Изменить мои настройки уведомлений",
            description = "reviewReminders — присылать ли письмо-напоминание об отзыве о доставленных товарах.")
    @PutMapping("/preferences")
    public ApiResponse<NotificationPreferencesResponse> updatePreferences(
            @Valid @RequestBody NotificationPreferencesRequest request) {
        return ApiResponse.success(
                messageSource.getMessage("notification.preferences.saved", null,
                        CommonMessagesAutoConfiguration.DEFAULT_LOCALE),
                preferenceService.update(SecurityUtils.currentUserId(), request));
    }
}
