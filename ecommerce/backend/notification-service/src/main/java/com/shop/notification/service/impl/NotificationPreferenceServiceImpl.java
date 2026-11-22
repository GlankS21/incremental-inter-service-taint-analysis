package com.shop.notification.service.impl;

import com.shop.notification.dto.request.NotificationPreferencesRequest;
import com.shop.notification.dto.response.NotificationPreferencesResponse;
import com.shop.notification.entity.NotificationPreference;
import com.shop.notification.repository.NotificationPreferenceRepository;
import com.shop.notification.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse get(UUID userId) {
        return new NotificationPreferencesResponse(reviewRemindersEnabled(userId));
    }

    @Override
    @Transactional
    public NotificationPreferencesResponse update(UUID userId, NotificationPreferencesRequest request) {
        preferenceRepository.upsert(userId, request.reviewReminders(), clock.instant());
        log.info("User {} turned review reminders {}", userId, request.reviewReminders() ? "on" : "off");
        return new NotificationPreferencesResponse(request.reviewReminders());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean reviewRemindersEnabled(UUID userId) {
        return preferenceRepository.findById(userId)
                .map(NotificationPreference::isReviewReminders)
                .orElse(NotificationPreference.DEFAULT_REVIEW_REMINDERS);
    }
}
