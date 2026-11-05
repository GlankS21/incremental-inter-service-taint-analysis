package com.shop.payment.controller;

import com.shop.common.dto.ApiResponse;
import com.shop.common.security.SecurityUtils;
import com.shop.payment.dto.request.MockCardPaymentRequest;
import com.shop.payment.dto.response.MockCardPaymentResponse;
import com.shop.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Backs the card form shown in place of the YooKassa widget, only when {@code PAYMENT_PROVIDER=mock}.
 */
@Tag(name = "Тестовые платежи", description = "Локальная имитация виджета ЮKassa (PAYMENT_PROVIDER=mock)")
@RestController
@RequestMapping("/api/payments/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentController {

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yy");

    private final PaymentService paymentService;

    @Operation(summary = "Оплатить тестовый платёж тестовой картой",
            description = "Только владелец заказа. Результат соответствует таблице тестовых карт ЮKassa и "
                    + "обрабатывается так же, как уведомление платёжного сервиса.")
    @PostMapping("/{paymentId}/pay")
    public ApiResponse<MockCardPaymentResponse> pay(@PathVariable("paymentId") String paymentId,
                                                    @Valid @RequestBody MockCardPaymentRequest request) {
        return ApiResponse.success(paymentService.payWithMockCard(paymentId, request.cardNumber(),
                YearMonth.parse(request.expiry(), EXPIRY), request.threeDSecureCode(), SecurityUtils.currentUser()));
    }
}
