package com.shop.user.service.impl;

import com.shop.common.dto.PageResponse;
import com.shop.common.event.UserRegisteredEvent;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.user.dto.request.UpdateProfileRequest;
import com.shop.user.dto.response.UserProfileResponse;
import com.shop.user.entity.UserProfile;
import com.shop.user.mapper.UserProfileMapper;
import com.shop.user.repository.UserProfileRepository;
import com.shop.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    static final String PROFILE_NOT_FOUND = "profile.error.not-found";
    private static final String ID = "id";

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;
    private final Clock clock;

    @Override
    @Transactional
    public UserProfileResponse getOrCreateProfile(UUID userId, String email) {
        return userProfileMapper.toResponse(findOrCreate(userId, email));
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, String email, UpdateProfileRequest request) {
        UserProfile profile = findOrCreate(userId, email);
        userProfileMapper.updateFromRequest(request, profile);
        return userProfileMapper.toResponse(userProfileRepository.saveAndFlush(profile));
    }

    @Override
    @Transactional
    public void createProfileFromRegistration(UserRegisteredEvent event) {
        userProfileRepository.findById(event.userId()).ifPresentOrElse(
                existing -> {
                    if (existing.getFirstName() == null && existing.getLastName() == null) {
                        existing.setFirstName(event.firstName());
                        existing.setLastName(event.lastName());
                        log.info("Completed lazily created profile {} from registration event", existing.getId());
                    } else {
                        log.debug("Profile {} already exists, registration event ignored", existing.getId());
                    }
                },
                () -> {
                    userProfileRepository.save(UserProfile.builder()
                            .id(event.userId())
                            .email(normalize(event.email()))
                            .firstName(event.firstName())
                            .lastName(event.lastName())
                            .build());
                    log.info("Created profile {} from registration event", event.userId());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserProfileResponse> searchProfiles(String keyword, Pageable pageable) {
        Pageable ordered = withIdTieBreaker(pageable);
        Page<UserProfile> page = StringUtils.hasText(keyword)
                ? userProfileRepository.search(escapeLike(keyword.trim()), ordered)
                : userProfileRepository.findAll(ordered);
        return PageResponse.from(page, userProfileMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        return userProfileRepository.findById(userId)
                .map(userProfileMapper::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of(PROFILE_NOT_FOUND));
    }

    /**
     * The insert skips a profile created meanwhile by a concurrent first access (or the registration
     * event), which then waits for that transaction and reads its profile.
     */
    private UserProfile findOrCreate(UUID userId, String email) {
        return userProfileRepository.findById(userId).orElseGet(() -> {
            if (userProfileRepository.insertIfAbsent(userId, normalize(email), clock.instant()) > 0) {
                log.info("Created missing profile {} on first access", userId);
            }
            return userProfileRepository.findById(userId)
                    .orElseThrow(() -> ResourceNotFoundException.of(PROFILE_NOT_FOUND));
        });
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The requested order completed by the id, so that profiles created at the same instant keep their
     * order from one page to the next.
     */
    private static Pageable withIdTieBreaker(Pageable pageable) {
        if (pageable.isUnpaged() || pageable.getSort().getOrderFor(ID) != null) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by(Sort.Order.desc(ID))));
    }

    /**
     * Makes {@code %}, {@code _} and {@code \} match themselves in a LIKE pattern escaped by {@code \}.
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
