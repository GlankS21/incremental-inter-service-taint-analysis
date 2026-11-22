package com.shop.payment.gateway.yookassa;

import com.shop.common.util.TextUtils;
import com.shop.payment.config.PaymentProperties;
import com.shop.payment.entity.PaymentProvider;
import com.shop.payment.gateway.GatewayException;
import com.shop.payment.gateway.PaymentGateway;
import com.shop.payment.gateway.yookassa.dto.YooKassaAmount;
import com.shop.payment.gateway.yookassa.dto.YooKassaConfirmation;
import com.shop.payment.gateway.yookassa.dto.YooKassaError;
import com.shop.payment.gateway.yookassa.dto.YooKassaPayment;
import com.shop.payment.gateway.yookassa.dto.YooKassaPaymentRequest;
import com.shop.payment.gateway.yookassa.dto.YooKassaRefund;
import com.shop.payment.gateway.yookassa.dto.YooKassaRefundRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * YooKassa API v3 ({@code https://api.yookassa.ru/v3}), HTTP Basic authentication with the shop id and
 * the secret key. Payments are created with automatic capture and an embedded confirmation: the
 * customer pays in the YooKassa widget on the shop's page (3-D Secure included), initialised with the
 * confirmation token of the payment.
 * <p>
 * Status codes 429 and 5xx, timeouts and network errors are temporary (the same call with the same
 * idempotence key may be repeated); other 4xx are final rejections.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "yookassa")
public class YooKassaPaymentGateway implements PaymentGateway {

    static final String IDEMPOTENCE_KEY_HEADER = "Idempotence-Key";
    static final int MAX_DESCRIPTION_LENGTH = 128;

    private final RestClient restClient;

    @Autowired
    public YooKassaPaymentGateway(RestClient.Builder restClientBuilder, PaymentProperties properties) {
        this(properties, restClientBuilder.clone().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.yookassa().connectTimeout())
                        .withReadTimeout(properties.yookassa().readTimeout()))));
    }

    /**
     * @param restClientBuilder builder whose request factory is already set (tests bind a mock server)
     */
    YooKassaPaymentGateway(PaymentProperties properties, RestClient.Builder restClientBuilder) {
        PaymentProperties.YooKassa settings = properties.yookassa();
        if (!StringUtils.hasText(settings.shopId()) || !StringUtils.hasText(settings.secretKey())) {
            throw new IllegalStateException(
                    "PAYMENT_PROVIDER=yookassa requires YOOKASSA_SHOP_ID and YOOKASSA_SECRET_KEY to be set");
        }
        this.restClient = restClientBuilder
                .baseUrl(settings.apiUrl())
                .defaultHeaders(headers -> headers.setBasicAuth(settings.shopId(), settings.secretKey()))
                .build();
        log.info("Payments use YooKassa ({}, shop {})", settings.apiUrl(), settings.shopId());
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.YOOKASSA;
    }

    @Override
    public GatewayPayment createPayment(CreatePaymentRequest request) {
        YooKassaPaymentRequest body = new YooKassaPaymentRequest(
                YooKassaAmount.of(request.amount(), request.currency()),
                true,
                YooKassaConfirmation.embedded(YooKassaConfirmation.RUSSIAN),
                truncate(request.description()),
                request.metadata().isEmpty() ? null : request.metadata());
        YooKassaPayment payment = call("create payment", () -> restClient.post()
                .uri("/payments")
                .header(IDEMPOTENCE_KEY_HEADER, request.idempotenceKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(YooKassaPayment.class));
        log.info("YooKassa payment {} created for order {} ({})", payment.id(), request.orderNumber(), payment.status());
        return toGatewayPayment(payment);
    }

    @Override
    public GatewayPayment getPayment(String paymentId) {
        return toGatewayPayment(call("get payment", () -> restClient.get()
                .uri("/payments/{id}", paymentId)
                .retrieve()
                .body(YooKassaPayment.class)));
    }

    @Override
    public GatewayRefund createRefund(CreateRefundRequest request) {
        YooKassaRefundRequest body = new YooKassaRefundRequest(request.paymentId(),
                YooKassaAmount.of(request.amount(), request.currency()), truncate(request.description()));
        YooKassaRefund refund = call("create refund", () -> restClient.post()
                .uri("/refunds")
                .header(IDEMPOTENCE_KEY_HEADER, request.idempotenceKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(YooKassaRefund.class));
        log.info("YooKassa refund {} created for payment {} ({})", refund.id(), request.paymentId(), refund.status());
        return toGatewayRefund(refund);
    }

    @Override
    public GatewayRefund getRefund(String refundId) {
        return toGatewayRefund(call("get refund", () -> restClient.get()
                .uri("/refunds/{id}", refundId)
                .retrieve()
                .body(YooKassaRefund.class)));
    }

    private <T> T call(String operation, Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (RestClientResponseException ex) {
            throw translate(operation, ex);
        } catch (ResourceAccessException ex) {
            throw GatewayException.unavailable("YooKassa unreachable (%s): %s".formatted(operation, ex.getMessage()), ex);
        } catch (RestClientException ex) {
            throw GatewayException.unavailable("Unexpected YooKassa response (%s): %s"
                    .formatted(operation, ex.getMessage()), ex);
        }
        if (body == null) {
            throw GatewayException.unavailable("Empty YooKassa response (%s)".formatted(operation), null);
        }
        return body;
    }

    private GatewayException translate(String operation, RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        YooKassaError error = readError(ex);
        String detail = error == null
                ? ex.getResponseBodyAsString()
                : "%s: %s%s".formatted(error.code(), error.description(),
                error.parameter() == null ? "" : " (parameter " + error.parameter() + ")");
        String message = "YooKassa %s failed with HTTP %d: %s".formatted(operation, status.value(), detail);
        if (status.is5xxServerError() || status.value() == 429) {
            return GatewayException.unavailable(message, ex);
        }
        if (status.value() == 401 || status.value() == 403) {
            log.error("YooKassa refused the credentials: check YOOKASSA_SHOP_ID and YOOKASSA_SECRET_KEY");
        }
        return GatewayException.rejected(message);
    }

    private static YooKassaError readError(RestClientResponseException ex) {
        try {
            return ex.getResponseBodyAs(YooKassaError.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static GatewayPayment toGatewayPayment(YooKassaPayment payment) {
        if (payment.id() == null) {
            throw GatewayException.unavailable("YooKassa payment without id", null);
        }
        return new GatewayPayment(
                payment.id(),
                toStatus(payment.status()),
                payment.amount() == null ? null : payment.amount().toBigDecimal(),
                payment.amount() == null ? null : payment.amount().currency(),
                payment.confirmation() == null ? null : payment.confirmation().confirmationToken(),
                payment.cancellationDetails() == null ? null : payment.cancellationDetails().reason(),
                payment.metadata());
    }

    private static GatewayRefund toGatewayRefund(YooKassaRefund refund) {
        if (refund.id() == null) {
            throw GatewayException.unavailable("YooKassa refund without id", null);
        }
        return new GatewayRefund(
                refund.id(),
                refund.paymentId(),
                toStatus(refund.status()),
                refund.amount() == null ? null : refund.amount().toBigDecimal(),
                refund.amount() == null ? null : refund.amount().currency(),
                refund.cancellationDetails() == null ? null : refund.cancellationDetails().reason());
    }

    static Status toStatus(String status) {
        if (status == null) {
            return Status.PENDING;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "succeeded" -> Status.SUCCEEDED;
            case "canceled" -> Status.CANCELED;
            case "waiting_for_capture" -> Status.WAITING_FOR_CAPTURE;
            default -> Status.PENDING;
        };
    }

    private static String truncate(String description) {
        return TextUtils.truncate(description, MAX_DESCRIPTION_LENGTH);
    }
}
