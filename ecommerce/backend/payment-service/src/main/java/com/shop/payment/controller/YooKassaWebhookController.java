package com.shop.payment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BadRequestException;
import com.shop.payment.gateway.yookassa.dto.YooKassaNotification;
import com.shop.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP notifications of YooKassa (configured in the YooKassa dashboard, see README). Open without a
 * token: the notified object is read again from the YooKassa API before anything is changed, so a
 * forged notification has no effect. The body is stored as received.
 */
@Tag(name = "Вебхуки", description = "Уведомления платёжного сервиса")
@RestController
@RequestMapping("/api/payments/webhooks")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "yookassa")
public class YooKassaWebhookController {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Уведомление ЮKassa",
            description = "События payment.succeeded, payment.canceled, payment.waiting_for_capture и "
                    + "refund.succeeded. После обработки возвращает 200; при любом другом статусе ЮKassa "
                    + "повторяет отправку.")
    @SecurityRequirements
    @PostMapping(path = "/yookassa", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> notify(@RequestBody String body) {
        YooKassaNotification notification;
        try {
            notification = objectMapper.readValue(body, YooKassaNotification.class);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("payment.error.invalid-notification");
        }
        if (notification == null || notification.object() == null || !StringUtils.hasText(notification.object().id())
                || !StringUtils.hasText(notification.event())) {
            throw new BadRequestException("payment.error.invalid-notification");
        }
        paymentService.handleProviderNotification(notification.event(), notification.object().id(), body);
        return ResponseEntity.ok().build();
    }
}
