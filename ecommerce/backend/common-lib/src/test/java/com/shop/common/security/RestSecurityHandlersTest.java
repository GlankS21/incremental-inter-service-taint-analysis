package com.shop.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;

class RestSecurityHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MessageSource messageSource = new CommonMessagesAutoConfiguration().messageSource();

    @Test
    void missingAuthentication_returnsRussian401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint(objectMapper, messageSource)
                .commence(request(), response, new InsufficientAuthenticationException("no token"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("message").asText()).isEqualTo("Требуется вход в аккаунт");
    }

    @Test
    void invalidToken_returnsRussian401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint(objectMapper, messageSource)
                .commence(request(), response, new InvalidBearerTokenException("expired"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("message").asText())
                .isEqualTo("Токен доступа недействителен или истёк, войдите снова");
    }

    @Test
    void accessDenied_returnsRussian403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler(objectMapper, messageSource)
                .handle(request(), response, new AccessDeniedException("denied"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.get("message").asText()).isEqualTo("Недостаточно прав для этого действия");
        assertThat(body.get("path").asText()).isEqualTo("/api/orders/admin");
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/orders/admin");
    }
}
