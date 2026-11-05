package com.shop.notification.template;

import com.shop.common.event.OrderCancelledEvent;
import com.shop.common.event.OrderConfirmedEvent;
import com.shop.common.event.OrderCreatedEvent;
import com.shop.common.event.OrderDeliveredEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.common.event.PasswordResetRequestedEvent;
import com.shop.common.event.ReviewReminderDueEvent;
import com.shop.common.event.UserRegisteredEvent;
import com.shop.notification.config.NotificationProperties;
import com.shop.notification.entity.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plain-text e-mails in Russian, the language of the shop's customers, rendered from events.
 * Amounts are written as {@code 1 234,50 ₽} and times as {@code dd.MM.yyyy HH:mm}, Moscow time.
 */
@Component
@RequiredArgsConstructor
public class NotificationTemplates {

    static final ZoneId SHOP_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(SHOP_ZONE);
    /** {@code PaymentMethod.COD} of order-service, as carried by {@link OrderConfirmedEvent#paymentMethod()}. */
    private static final String CASH_ON_DELIVERY = "COD";

    private final NotificationProperties properties;

    public RenderedNotification welcome(UserRegisteredEvent event) {
        String greeting = StringUtils.hasText(event.firstName())
                ? "Здравствуйте, %s!".formatted(event.firstName().trim())
                : "Здравствуйте!";
        String body = """
                %s

                Спасибо за регистрацию в магазине %s. Ваш аккаунт привязан к адресу %s.
                Теперь вы можете оформлять заказы, следить за их статусом и сохранять адреса доставки.

                %s""".formatted(greeting, shop(), event.email(), signature());
        return new RenderedNotification(NotificationType.WELCOME, event.userId(), event.email(), null,
                "[%s] Добро пожаловать в %s".formatted(shop(), shop()), body);
    }

    /**
     * The link opens the reset page of the storefront; the token is URL-encoded. The validity is the
     * lifetime chosen by auth-service, rounded up to whole minutes.
     */
    public RenderedNotification passwordReset(PasswordResetRequestedEvent event) {
        String link = "%s/reset-password?token=%s".formatted(properties.frontendUrl(),
                URLEncoder.encode(event.token(), StandardCharsets.UTF_8));
        String body = """
                Здравствуйте!

                Мы получили запрос на сброс пароля для аккаунта %s в магазине %s.
                Чтобы задать новый пароль, перейдите по ссылке:

                %s

                Ссылка действительна %d мин. и может быть использована только один раз.
                Если вы не запрашивали сброс пароля, просто проигнорируйте это письмо: ваш пароль останется прежним.

                %s""".formatted(event.email(), shop(), link, validityMinutes(event), signature());
        return new RenderedNotification(NotificationType.PASSWORD_RESET, event.userId(), event.email(), null,
                "[%s] Сброс пароля".formatted(shop()), body, event.expiresAt());
    }

    public RenderedNotification orderPlaced(OrderCreatedEvent event) {
        String lines = event.items().stream()
                .map(item -> "  - %s, %d шт. — %s".formatted(item.productName(), item.quantity(),
                        MoneyFormatter.format(lineTotal(item), event.currency())))
                .collect(Collectors.joining("\n"));
        long units = event.items().stream().mapToLong(OrderItemPayload::quantity).sum();
        String body = """
                Здравствуйте!

                Мы получили ваш заказ %s от %s. В заказе %s:

                %s

                Итого к оплате с учётом доставки: %s

                Мы резервируем товары и обрабатываем оплату. Когда заказ будет подтверждён, мы пришлём ещё одно письмо.

                %s""".formatted(event.orderNumber(), dateTime(event.occurredAt()),
                RussianPlural.withCount(units, "товар", "товара", "товаров"), lines,
                MoneyFormatter.format(event.totalAmount(), event.currency()), signature());
        return new RenderedNotification(NotificationType.ORDER_PLACED, event.userId(), event.userEmail(),
                event.orderNumber(), "[%s] Заказ %s оформлен".formatted(shop(), event.orderNumber()), body);
    }

    /**
     * A cash-on-delivery order is confirmed as soon as the stock is reserved: the customer pays the courier,
     * so the mail must not say the payment went through.
     */
    public RenderedNotification orderConfirmed(OrderConfirmedEvent event) {
        if (CASH_ON_DELIVERY.equals(event.paymentMethod())) {
            String body = """
                    Здравствуйте!

                    Заказ %s на сумму %s подтверждён (%s).
                    Оплата при получении: вы рассчитаетесь с курьером, когда получите заказ.
                    Скоро мы передадим его в доставку.

                    %s""".formatted(event.orderNumber(), MoneyFormatter.format(event.totalAmount(), event.currency()),
                    dateTime(event.occurredAt()), signature());
            return new RenderedNotification(NotificationType.ORDER_CONFIRMED, event.userId(), event.userEmail(),
                    event.orderNumber(), "[%s] Заказ %s подтверждён".formatted(shop(), event.orderNumber()), body);
        }
        String body = """
                Здравствуйте!

                Оплата заказа %s на сумму %s прошла успешно (%s).
                Заказ подтверждён, скоро мы передадим его в доставку.

                %s""".formatted(event.orderNumber(), MoneyFormatter.format(event.totalAmount(), event.currency()),
                dateTime(event.occurredAt()), signature());
        return new RenderedNotification(NotificationType.ORDER_CONFIRMED, event.userId(), event.userEmail(),
                event.orderNumber(), "[%s] Заказ %s оплачен и подтверждён".formatted(shop(), event.orderNumber()),
                body);
    }

    public RenderedNotification orderCancelled(OrderCancelledEvent event) {
        String reason = StringUtils.hasText(event.reason()) ? event.reason().trim() : "не указана";
        String body = """
                Здравствуйте!

                Заказ %s отменён (%s).
                Причина: %s

                Если заказ был оплачен, деньги вернутся тем же способом, которым вы платили.

                %s""".formatted(event.orderNumber(), dateTime(event.occurredAt()), reason, signature());
        return new RenderedNotification(NotificationType.ORDER_CANCELLED, event.userId(), event.userEmail(),
                event.orderNumber(), "[%s] Заказ %s отменён".formatted(shop(), event.orderNumber()), body);
    }

    /**
     * «Оцените покупку»: shown in the account only (in-app), right after delivery. The products are counted
     * once each, whatever their quantity.
     */
    public RenderedNotification reviewRequest(OrderDeliveredEvent event) {
        List<String> names = event.items() == null ? List.of() : event.items().stream()
                .filter(Objects::nonNull)
                .map(OrderItemPayload::productName)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        String lines = names.stream().map(name -> "  - " + name).collect(Collectors.joining("\n"));
        String body = """
                Заказ %s доставлен (%s). Спасибо за покупку!

                Поделитесь впечатлением о %s — ваш отзыв поможет другим покупателям:

                %s

                Оставить отзыв: %s""".formatted(event.orderNumber(), dateTime(event.occurredAt()),
                RussianPlural.withCount(names.size(), "товаре", "товарах", "товарах"), lines, reviewsLink());
        return new RenderedNotification(NotificationType.REVIEW_REQUEST, event.userId(), event.userEmail(),
                event.orderNumber(), "Оцените покупку", body);
    }

    /**
     * One e-mail per delivered order: each product links to its review form on the "Мои отзывы" page.
     *
     * @param recipient the customer's address (the reminder event carries no personal data)
     */
    public RenderedNotification reviewReminder(ReviewReminderDueEvent event, String recipient) {
        String lines = event.items().stream()
                .map(item -> "  - %s\n    %s?product=%d".formatted(item.productName(), reviewsLink(), item.productId()))
                .collect(Collectors.joining("\n"));
        String order = StringUtils.hasText(event.orderNumber()) ? " " + event.orderNumber() : "";
        String body = """
                Здравствуйте!

                Недавно мы доставили ваш заказ%s. Вы ещё не оценили %s:

                %s

                Оценка займёт минуту: выберите звёзды и, если хотите, напишите пару слов.
                Все товары, ожидающие отзыва: %s

                Это единственное напоминание по этому заказу. Отключить напоминания об отзывах можно в разделе «Уведомления»: %s/account/notifications

                %s""".formatted(order, RussianPlural.withCount(event.items().size(), "товар", "товара", "товаров"),
                lines, reviewsLink(), properties.frontendUrl(), signature());
        return new RenderedNotification(NotificationType.REVIEW_REMINDER, event.userId(), recipient,
                event.orderNumber(), "[%s] Оцените покупку".formatted(shop()), body);
    }

    private String reviewsLink() {
        return properties.frontendUrl() + "/account/reviews";
    }

    /**
     * @return {@code dd.MM.yyyy HH:mm} in Moscow time
     */
    static String dateTime(Instant instant) {
        return instant == null ? "—" : DATE_TIME.format(instant);
    }

    private static long validityMinutes(PasswordResetRequestedEvent event) {
        long seconds = Duration.between(event.occurredAt(), event.expiresAt()).toSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    private static BigDecimal lineTotal(OrderItemPayload item) {
        return item.unitPrice() == null ? null : item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
    }

    private String shop() {
        return properties.shopName();
    }

    private String signature() {
        return "С уважением,\n" + shop();
    }

    /**
     * @param reference order number of order notifications
     * @param expiresAt end of validity of the link in the body; the e-mail is not sent afterwards
     */
    public record RenderedNotification(NotificationType type, UUID userId, String recipient, String reference,
                                       String subject, String body, Instant expiresAt) {

        public RenderedNotification(NotificationType type, UUID userId, String recipient, String reference,
                                    String subject, String body) {
            this(type, userId, recipient, reference, subject, body, null);
        }
    }
}
