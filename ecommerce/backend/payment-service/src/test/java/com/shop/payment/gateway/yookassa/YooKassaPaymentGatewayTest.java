package com.shop.payment.gateway.yookassa;

import com.shop.payment.config.PaymentProperties;
import com.shop.payment.gateway.GatewayException;
import com.shop.payment.gateway.PaymentGateway.CreatePaymentRequest;
import com.shop.payment.gateway.PaymentGateway.CreateRefundRequest;
import com.shop.payment.gateway.PaymentGateway.GatewayPayment;
import com.shop.payment.gateway.PaymentGateway.GatewayRefund;
import com.shop.payment.gateway.PaymentGateway.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YooKassaPaymentGatewayTest {

    private static final String API = "https://api.yookassa.test/v3";

    private MockRestServiceServer server;
    private YooKassaPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentProperties properties = new PaymentProperties(PaymentProperties.Provider.YOOKASSA, "RUB", null,
                new PaymentProperties.YooKassa(API, "123456", "test_secret", null, null), null, null);
        gateway = new YooKassaPaymentGateway(properties, builder);
    }

    @Test
    void createsAnEmbeddedPaymentWithAutomaticCapture() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("123456:test_secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(API + "/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(header("Idempotence-Key", "key-1"))
                .andExpect(content().json("""
                        {"amount":{"value":"1234.50","currency":"RUB"},
                         "capture":true,
                         "confirmation":{"type":"embedded","locale":"ru_RU"},
                         "description":"Заказ № ORD-1",
                         "metadata":{"transactionId":"key-1"}}
                        """, true))
                .andRespond(withSuccess("""
                        {"id":"2c5d1a3e-000f-5000-9000-1b2c3d4e5f60","status":"pending","paid":false,
                         "amount":{"value":"1234.50","currency":"RUB"},
                         "confirmation":{"type":"embedded","confirmation_token":"ct-2c5d1a3e-000f-5000-9000-1b2c3d4e5f60"},
                         "created_at":"2026-10-21T10:00:00.000Z","test":true,"refundable":false}
                        """, MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.createPayment(new CreatePaymentRequest("key-1", UUID.randomUUID(), "ORD-1",
                new BigDecimal("1234.5"), "RUB", "Заказ № ORD-1", Map.of("transactionId", "key-1")));

        server.verify();
        assertThat(payment.id()).isEqualTo("2c5d1a3e-000f-5000-9000-1b2c3d4e5f60");
        assertThat(payment.status()).isEqualTo(Status.PENDING);
        assertThat(payment.amount()).isEqualByComparingTo("1234.50");
        assertThat(payment.confirmationToken()).isEqualTo("ct-2c5d1a3e-000f-5000-9000-1b2c3d4e5f60");
    }

    @Test
    void readsTheStateOfAPayment() {
        server.expect(requestTo(API + "/payments/pay-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"pay-1","status":"canceled","paid":false,
                         "amount":{"value":"990.00","currency":"RUB"},
                         "cancellation_details":{"party":"payment_network","reason":"insufficient_funds"},
                         "metadata":{"transactionId":"t-1","orderId":"o-1"}}
                        """, MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.getPayment("pay-1");

        assertThat(payment.status()).isEqualTo(Status.CANCELED);
        assertThat(payment.cancellationReason()).isEqualTo("insufficient_funds");
        assertThat(payment.metadata()).containsEntry("transactionId", "t-1");
    }

    @Test
    void createsAndReadsRefunds() {
        server.expect(requestTo(API + "/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotence-Key", "refund-1"))
                .andExpect(content().json("""
                        {"payment_id":"pay-1","amount":{"value":"990.00","currency":"RUB"},
                         "description":"Возврат по заказу № ORD-1"}
                        """, true))
                .andRespond(withSuccess("""
                        {"id":"rf-1","payment_id":"pay-1","status":"pending","amount":{"value":"990.00","currency":"RUB"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/refunds/rf-1"))
                .andRespond(withSuccess("""
                        {"id":"rf-1","payment_id":"pay-1","status":"succeeded","amount":{"value":"990.00","currency":"RUB"}}
                        """, MediaType.APPLICATION_JSON));

        GatewayRefund created = gateway.createRefund(new CreateRefundRequest("refund-1", "pay-1",
                new BigDecimal("990"), "RUB", "Возврат по заказу № ORD-1"));
        GatewayRefund read = gateway.getRefund("rf-1");

        assertThat(created.status()).isEqualTo(Status.PENDING);
        assertThat(read.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(read.paymentId()).isEqualTo("pay-1");
    }

    @Test
    void clientErrorsAreFinalAndServerErrorsAreTemporary() {
        server.expect(requestTo(API + "/payments/unknown"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"type":"error","id":"e-1","code":"not_found","description":"Payment doesn't exist"}
                        """));
        server.expect(requestTo(API + "/payments/pay-2")).andRespond(withServerError());
        server.expect(requestTo(API + "/payments/pay-3")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> gateway.getPayment("unknown"))
                .isInstanceOfSatisfying(GatewayException.class, ex -> {
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getReasonCode()).isEqualTo(GatewayException.PROVIDER_REJECTED);
                    assertThat(ex.getMessage()).contains("not_found");
                });
        assertThatThrownBy(() -> gateway.getPayment("pay-2"))
                .isInstanceOfSatisfying(GatewayException.class, ex -> assertThat(ex.isRetryable()).isTrue());
        assertThatThrownBy(() -> gateway.getPayment("pay-3"))
                .isInstanceOfSatisfying(GatewayException.class, ex -> assertThat(ex.isRetryable()).isTrue());
    }

    @Test
    void refusesToStartWithoutCredentials() {
        PaymentProperties properties = new PaymentProperties(PaymentProperties.Provider.YOOKASSA, "RUB", null,
                new PaymentProperties.YooKassa(API, "", "", null, null), null, null);

        assertThatThrownBy(() -> new YooKassaPaymentGateway(properties, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YOOKASSA_SHOP_ID");
    }

    @Test
    void mapsEveryStatus() {
        assertThat(YooKassaPaymentGateway.toStatus("succeeded")).isEqualTo(Status.SUCCEEDED);
        assertThat(YooKassaPaymentGateway.toStatus("canceled")).isEqualTo(Status.CANCELED);
        assertThat(YooKassaPaymentGateway.toStatus("waiting_for_capture")).isEqualTo(Status.WAITING_FOR_CAPTURE);
        assertThat(YooKassaPaymentGateway.toStatus("pending")).isEqualTo(Status.PENDING);
    }
}
