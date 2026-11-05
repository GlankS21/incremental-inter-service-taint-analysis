package com.shop.notification.service;

import com.shop.notification.dto.request.NotificationPreferencesRequest;
import com.shop.notification.dto.response.NotificationPreferencesResponse;

import java.util.UUID;

/**
 * Notification settings of a customer; a customer who never changed them has the defaults.
 */
public interface NotificationPreferenceService {

    NotificationPreferencesResponse get(UUID userId);

    NotificationPreferencesResponse update(UUID userId, NotificationPreferencesRequest request);

    boolean reviewRemindersEnabled(UUID userId);
}
