# notification-service

Отправляет письма при регистрации покупателя и по ходу выполнения заказа. Порт по умолчанию: **8088**. База данных: `notification_db`.

## Письма

| Событие (очередь) | Письмо |
|---|---|
| `user.registered` (`notification.user-events`) | Приветствие |
| `user.password-reset-requested` (`notification.user-events`) | Ссылка для сброса пароля |
| `order.created` (`notification.order-events`) | Заказ принят: список товаров, итоговая сумма |
| `order.confirmed` (`notification.order-events`) | Оплата прошла успешно, заказ подтверждён; для оплаты при получении (`paymentMethod` = `COD`) — заказ подтверждён, оплата курьеру |
| `order.cancelled` (`notification.order-events`) | Заказ отменён, с указанием причины |
| `order.delivered` (`notification.order-events`) | **Уведомление в личном кабинете** (тип `REVIEW_REQUEST`, канал `IN_APP`, письмо не отправляется): «Оцените покупку» — заказ доставлен, ссылка `<FRONTEND_URL>/account/reviews` |
| `review.reminder-due` (`notification.product-events`, exchange `shop.product.events`) | Письмо-напоминание (тип `REVIEW_REMINDER`) «Оцените покупку»: список ещё не оценённых товаров заказа со ссылками `<FRONTEND_URL>/account/reviews?product=<id>` и ссылка на отключение напоминаний `<FRONTEND_URL>/account/notifications` |

Содержимое — простой текст на русском языке, суммы в формате `25 140,00 ₽`, даты в часовом поясе Europe/Moscow.
Название магазина берётся из `SHOP_NAME`.

**Письмо для сброса пароля** (тип `PASSWORD_RESET`): текст на русском, ссылка
`<FRONTEND_URL>/reset-password?token=<URL-кодированный token>` (`FRONTEND_URL` по умолчанию `http://localhost:3000`,
завершающий `/` отбрасывается), срок действия в минутах, вычисляемый как `expiresAt − occurredAt`, и просьба
проигнорировать письмо, если сброс не запрашивался. Поскольку ссылка секретна, API (история покупателя и список
администратора) возвращает для этого типа `body = null`. Текст хранится только до доставки: после отправки (`SENT`)
или истечения срока ссылки (`EXPIRED`, срок берётся из `expiresAt` события) он заменяется на `[erased]`. Поэтому
доставленное или устаревшее письмо со ссылкой повторно не отправляется — покупатель запрашивает новую ссылку.
При `MAIL_ENABLED=false` все письма (включая ссылку) записываются в лог, чтобы разработчик мог взять ссылку во время
разработки, — не используйте этот режим в production.

## Напоминание об отзыве

- Когда отправлять, решает product-service: через `REVIEW_REMINDER_DELAY_DAYS` дней (по умолчанию 3) после доставки,
  **один раз на заказ** и только если в заказе остались товары без отзыва (см. README product-service).
- Событие не содержит e-mail: адрес берётся из последнего письма этому покупателю (`order.created`/`order.delivered`
  всегда приходят раньше). Нет адреса, пустой список товаров или напоминания отключены — письмо не создаётся (запись в лог).
- `event_id` уведомления = `eventId` события, который product-service выводит из id заказа, поэтому повторная доставка
  или повторная публикация не создаёт второе письмо.
- Покупатель отключает напоминания в «Уведомления» личного кабинета (`PUT /api/notifications/preferences`), настройка
  хранится в `notification_preferences` (нет строки — напоминания включены). Она касается только письма: уведомление
  «Оцените покупку» в личном кабинете создаётся всегда.
- Уведомления `IN_APP` сохраняются сразу со статусом `SENT` и не уходят в SMTP; повторно отправить их администратор
  не может (400 `notification.error.in-app-not-resendable`).

## Надёжность

- Каждое письмо сохраняется в таблицу `notifications` до отправки; `event_id` уникален, поэтому повторно доставленное
  событие не порождает второе письмо.
- Отправка SMTP выполняется **вне транзакции**. Ошибка отправки (SMTP недоступен) не портит сообщение: письмо помечается
  `FAILED` с текстом ошибки, а `NotificationRetryJob` повторяет отправку каждые `app.notification.retry.interval`
  (1 минута) до `max-attempts` (5) раз. После этого администратор может отправить письмо повторно вручную.
- Письма, оставшиеся в `PENDING` (сервис остановился между сохранением и отправкой), задача тоже отправляет повторно.
- Перед отправкой письмо «захватывается» условным `UPDATE … SET status = 'SENDING'`: задача повторной отправки на
  нескольких экземплярах и ручная повторная отправка администратором никогда не отправят одно письмо дважды. Письмо,
  оставшееся в `SENDING` дольше 10 минут (сервис упал во время отправки), снова становится `FAILED` и повторяется.
- Прочие ошибки обработки сообщений: 3 повторные попытки, затем `<queue>.dlq`. Сообщения из `<queue>.dlq`
  возвращаются в свою очередь общим `DeadLetterRedriver` (common-lib) до 5 раз; после этого остаются в DLQ с записью
  уровня ERROR в логе.

## Среда разработки: Mailpit

Docker Compose запускает [Mailpit](https://mailpit.axllent.org/) — тестовый SMTP, перехватывающий все письма без
отправки наружу. Письма можно посмотреть на **http://localhost:8025**. При запуске сервиса вне Docker:
`docker compose up -d mailpit`, сервис отправляет на SMTP `localhost:1025` (порт `MAILPIT_SMTP_PORT`).

`MAIL_ENABLED=false` заменяет SMTP записью писем в лог.

## API

| Method | Путь | Права | Описание |
|---|---|---|---|
| GET | `/api/notifications?page=&size=` | вход | Мои письма и уведомления, сначала новые; поле `channel` = `EMAIL` или `IN_APP` |
| GET | `/api/notifications/preferences` | вход | Мои настройки `{reviewReminders}` (по умолчанию `true`) |
| PUT | `/api/notifications/preferences` | вход | `{reviewReminders: true/false}` — включить/отключить письма-напоминания об отзыве; сообщение `notification.preferences.saved` |
| GET | `/api/notifications/admin?status=&type=` | ADMIN | Все письма, фильтр по `PENDING`/`SENT`/`FAILED` и типу |
| POST | `/api/notifications/admin/{id}/resend` | ADMIN | Повторная отправка письма (не для `IN_APP`) |

## Миграции

- `V4__review_requests.sql`: типы `REVIEW_REQUEST`, `REVIEW_REMINDER`; таблица `notification_preferences(user_id, review_reminders, updated_at)`

## Переменные окружения

`CONFIG_SERVER_*`, `EUREKA_*`, `RABBITMQ_*`, `JWT_SECRET`, `NOTIFICATION_DB_PASSWORD`; необязательные:
`NOTIFICATION_SERVICE_PORT`, `MAIL_HOST` (по умолчанию `localhost`, Docker: `mailpit`), `MAIL_PORT` (1025),
`MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, `MAIL_ENABLED`, `MAIL_FROM`, `SHOP_NAME`,
`FRONTEND_URL` (по умолчанию `http://localhost:3000`, используется для ссылок в письмах).

## Тесты

```bash
cd backend
./mvnw -pl notification-service test
```

- Unit-тесты: `NotificationTemplatesTest` (содержимое, формат сумм, ссылка для сброса пароля: URL-кодирование, число минут),
  `NotificationMapperTest` (скрытие содержимого писем `PASSWORD_RESET`)
- Testcontainers: `NotificationIntegrationTest` — настоящие события в RabbitMQ, SMTP эмулируется mock-объектом:
  без дублей, повторная отправка при ошибке, ограничение числа попыток, повторная отправка администратором, письмо
  для сброса пароля (ссылка сохраняется, API не возвращает содержимое)
- Testcontainers: `ReviewRemindersIntegrationTest` — `order.delivered` создаёт одно уведомление в кабинете без письма,
  `review.reminder-due` — одно письмо со ссылками (повтор события не дублирует), отключённые напоминания и чужие
  настройки (пользователь видит и меняет только свои)
