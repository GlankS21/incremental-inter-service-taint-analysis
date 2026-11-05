# Тестирование

## Уровни тестирования

| Уровень | Что проверяется | Инструменты | Нужен Docker |
|---|---|---|---|
| Unit | Бизнес-правила в сервисах и сущностях: расчёт цен корзины, переходы статусов заказа, резервирование/снятие резерва, эмулированная оплата, ротация токенов, шаблоны писем… | JUnit 5, Mockito, AssertJ | Нет |
| Integration | Реальный сервис с настоящими PostgreSQL и RabbitMQ: API через MockMvc, безопасность JWT, миграции Flyway, консьюмеры и outbox Saga, поиск по каталогу (морфология, опечатки, раскладка, синонимы, ранжирование, SQL-инъекции, фасеты, подсказки) | Spring Boot Test, Testcontainers | Да (автоматически пропускаются без Docker) |
| Gateway | Маршрутизация, проверка JWT, rate limit, адрес клиента за доверенным прокси | WebTestClient, unit-тесты | Нет |
| Frontend | Чтение JWT, определение IP клиента, single-flight обновление токена, защита от open redirect, форматирование денег/дат (RUB, ru-RU), параметры URL | Vitest | Нет |
| End-to-end | Вся система в Docker через сайт: покупка с полным прохождением Saga, администрирование, CSRF, одновременное обновление токена, rate limit, Swagger | `scripts/smoke-test.sh` (curl) | Да |

Сервисы, вызываемые через Feign (product, inventory), в интеграционных тестах подменяются на уровне Feign-клиента,
поэтому каждый сервис тестируется независимо; взаимодействие сервисов проверяется
smoke-тестом.

## Количество

Результат `./mvnw verify` (27.10.2026, Docker запущен, ни один тест не пропущен). Покрытие взято из
JaCoCo, учитываются только тесты самого модуля:

| Модуль | Тесты | Покрытие строк | Покрытие ветвлений |
|---|---:|---:|---:|
| common-lib | 27 | 48% | 55% |
| config-server | 3 | 100% | — |
| discovery-server | 3 | 100% | — |
| api-gateway | 22 | 81% | 56% |
| auth-service | 50 | 89% | 89% |
| user-service | 59 | 91% | 81% |
| product-service | 107 | 92% | 82% |
| inventory-service | 33 | 93% | 76% |
| cart-service | 51 | 95% | 87% |
| order-service | 52 | 91% | 81% |
| payment-service | 40 | 89% | 68% |
| notification-service | 46 | 93% | 78% |
| **Backend** | **493** | | |
| Frontend (Vitest) | 74 | | |
| Smoke-тест | 83 шага | | |

Покрытие common-lib низкое, потому что большая часть его кода (auto-configuration, передача correlation id через
Feign и RabbitMQ, общая обработка ошибок) выполняется только внутри сервисов и проверяется интеграционными
тестами этих сервисов, которые JaCoCo не засчитывает модулю common-lib.

## Запуск

### Backend

```bash
cd backend
./mvnw clean verify                          # все тесты + отчёт о покрытии
./mvnw -pl order-service -am verify          # один сервис (вместе с common-lib)
./mvnw -pl cart-service test -Dtest=CartServiceImplTest   # один тестовый класс
```

- Чтобы интеграционные тесты выполнялись, Docker Desktop должен быть запущен; иначе они помечаются
  как *skipped*, а сборка всё равно проходит успешно.
- Отчёт о покрытии: `backend/<module>/target/site/jacoco/index.html` (автоматически сгенерированные мапперы
  MapStruct и классы `*Application` не учитываются).

### Frontend

```bash
cd frontend
npm run typecheck && npm run lint && npm test && npm run build
```

### Smoke-тест всей системы

```bash
docker compose up -d --build --wait
ADMIN_EMAIL=admin@shop.local ADMIN_PASSWORD='<ADMIN_PASSWORD>' scripts/smoke-test.sh
```

Скрипт создаёт реальные данные (категорию, товар, покупателя, заказы), поэтому запускайте его только в локальной
или тестовой среде. Необязательные переменные: `FRONTEND_URL` (по умолчанию `http://localhost:3000`), `GATEWAY_URL`
(`http://localhost:8080`). Для шага проверки rate limit по браузеру нужно, чтобы был задан `TRUSTED_PROXY_SECRET`
и `TRUSTED_PROXY_HOPS=0`. Тестовые данные скрипта — российские: покупатель «Иван Петров», телефон `+79161234567`,
адрес «ул. Тверская, д. 1, кв. 10», Москва, `countryCode` `RU`.

Этапы проверки:

1. OpenAPI 8 сервисов через gateway и Swagger UI.
2. Публичные страницы, заголовки безопасности, перенаправление без входа, блокировка запросов с чужого origin.
3. Администратор: вход (cookie HttpOnly), все страницы администрирования, создание категории и товара, установка остатка.
4. Покупатель: регистрация, нет доступа к страницам/API администрирования, корзина, сохранение адреса, заказ с оплатой
   картой, `Idempotency-Key` возвращает тот же заказ, Saga переводит заказ в `PAID`, платёж `SUCCEEDED`, товар
   уходит из корзины, администратор переводит заказ в `SHIPPED`.
5. Истёкший access token при 5 одновременных запросах: все успешны, выпущен только один новый refresh token,
   сеанс не отозван.
6. Выход; rate limit блокирует один адрес, не затрагивая другие.

После создания товара скрипт проверяет поиск: товар находится по SKU, событие `stock.level-changed` помечает его
как имеющийся в наличии, подсказки исправляют раскладку (`ntcnjdst` → «тестовые»), администратору доступна статистика
поиска и страницы `/admin/search`, `/admin/search/synonyms`.

## CI

`.github/workflows/ci.yml` запускается при каждом push в `main` и на каждый pull request:

| Job | Содержание |
|---|---|
| Backend | `mvnw clean verify` на JDK 21 (на runner есть Docker, поэтому интеграционные тесты выполняются полностью), прикладывается отчёт JaCoCo |
| Frontend | `npm ci`, typecheck, lint, Vitest, `next build` на Node 22 |
| Compose | `docker compose config` с `.env.example` |

Smoke-тест не запускается в CI, потому что требует поднять 15 контейнеров; запускайте его перед выпуском.
