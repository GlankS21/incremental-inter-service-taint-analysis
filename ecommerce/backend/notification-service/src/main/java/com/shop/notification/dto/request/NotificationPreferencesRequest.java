package com.shop.notification.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * @param reviewReminders whether to receive the e-mail reminding to review delivered products
 */
public record NotificationPreferencesRequest(
        @NotNull Boolean reviewReminders
) {
}
