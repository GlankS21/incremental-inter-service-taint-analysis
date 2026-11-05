package com.shop.gateway.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ErrorResponse;
import com.shop.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ErrorResponseWriter writer = new ErrorResponseWriter(objectMapper,
            new CommonMessagesAutoConfiguration().messageSource());

    @Test
    void genericMessageOfTheCodeIsInRussian() {
        assertThat(writer.message(ErrorCode.GATEWAY_TIMEOUT, ErrorCode.messageKeyOf(ErrorCode.GATEWAY_TIMEOUT)))
                .isEqualTo("Сервис не ответил вовремя, попробуйте позже");
    }

    @Test
    void gatewayKeyIsResolvedWithItsArguments() {
        assertThat(writer.message(ErrorCode.SERVICE_UNAVAILABLE, "gateway.error.service-unavailable", "cart-service"))
                .isEqualTo("Сервис cart-service временно недоступен, попробуйте позже");
    }

    @Test
    void unknownKeyFallsBackToTheGenericMessageOfTheCode() {
        assertThat(writer.message(ErrorCode.BAD_REQUEST, "gateway.error.no-such-key"))
                .isEqualTo("Некорректный запрос");
    }

    @Test
    void writesThePlatformErrorBodyInRussian() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));

        writer.write(exchange, ErrorCode.TOO_MANY_REQUESTS).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String json = exchange.getResponse().getBodyAsString().block();
        ErrorResponse body = objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), ErrorResponse.class);
        assertThat(body.getCode()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.getMessage()).isEqualTo("Слишком много запросов, попробуйте позже");
        assertThat(body.getPath()).isEqualTo("/api/orders");
    }
}
