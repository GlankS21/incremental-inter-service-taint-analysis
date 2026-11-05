package com.shop.notification.dto.response;

/**
 * Notification settings of the caller (defaults when never changed).
 *
 * @param reviewReminders whether the e-mail reminding to review delivered products is sent
 */
public record NotificationPreferencesResponse(
        boolean reviewReminders
) {
}
