package com.shop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Per-client-IP rate limiting. Each request uses exactly one policy (its own token bucket): the auth
 * policy for {@code authPaths}, the search policy for {@code searchPaths}, the default policy otherwise.
 *
 * @param enabled       master switch
 * @param defaultPolicy limit applied to every other request
 * @param authPolicy    stricter limit for credential endpoints (brute-force protection)
 * @param authPaths     path patterns that use {@code authPolicy}
 * @param searchPolicy  limit of the type-ahead endpoints, called on every keystroke; the default policy
 *                      when not set
 * @param searchPaths   path patterns that use {@code searchPolicy}
 */
@ConfigurationProperties(prefix = "app.gateway.rate-limit")
public record RateLimitProperties(boolean enabled,
                                  RateLimitPolicy defaultPolicy,
                                  RateLimitPolicy authPolicy,
                                  List<String> authPaths,
                                  RateLimitPolicy searchPolicy,
                                  List<String> searchPaths) {

    public RateLimitProperties {
        authPaths = authPaths == null ? List.of() : List.copyOf(authPaths);
        searchPolicy = searchPolicy == null ? defaultPolicy : searchPolicy;
        searchPaths = searchPaths == null ? List.of() : List.copyOf(searchPaths);
    }
}
