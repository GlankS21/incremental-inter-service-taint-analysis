# product-service

Каталог товаров: дерево категорий, товары, изображения, умный поиск (PostgreSQL full-text + pg_trgm, без Elasticsearch). Порт по умолчанию: **8083**. База данных: `product_db`.

Чтение данных публичное (gateway открывает `GET /api/products/**`, `GET /api/categories/**`); любые операции записи
категорий/товаров требуют `ROLE_ADMIN`. Собственные отзывы (`/api/reviews/**`) требуют входа. Покупатели видят только товары `ACTIVE`; администратор (с access token) видит также `DRAFT`
и `ARCHIVED` — для остальных скрытый товар возвращает 404.

## Модель

| Понятие | Правило |
|---|---|
| Категория | Дерево **из 2 уровней**: корневые категории и их непосредственные дочерние. Фильтр по корневой категории включает дочерние |
| Slug | Генерируется из названия, если не передан (кириллица транслитерируется латиницей: «Ноутбуки и ультрабуки» → `noutbuki-i-ultrabuki`); при совпадении slug товара добавляется SKU. При обновлении slug меняется, только если передан явно |
| SKU | `^[A-Z0-9][A-Z0-9-]{2,63}$`, уникален, **не изменяется** после создания (на него ссылаются склад и заказы) |
| Цена | `price > 0`, не более 2 знаков после запятой; `compareAtPrice` (зачёркнутая цена) должна быть больше `price`. Одна валюта для всего магазина: `CATALOG_CURRENCY` (по умолчанию `RUB`) |
| Статус | `DRAFT` (по умолчанию при создании) → `ACTIVE` → `ARCHIVED`. `DELETE` = перевод в `ARCHIVED` (мягкое удаление) |
| Изображения | Не более 10 URL, первое изображение — миниатюра. URL — абсолютный http(s) **или** относительный URL загруженного изображения (`/api/products/images/<uuid>.<ext>`). Если при обновлении передан `images`, список заменяется целиком, если не передан — остаётся без изменений |
| Отзывы | Оставить отзыв может только тот, кто **получил товар** (есть заказ `DELIVERED` с этим товаром); один отзыв на товар от каждого пользователя (повторная отправка = изменение). `rating` 1–5, `comment` ≤ 2000 символов (trim, пусто → `null`), `authorName` ≤ 60 символов (trim) |
| Средняя оценка | `ratingAverage` (округление HALF_UP до 1 знака, 0 при отсутствии отзывов) и `ratingCount` хранятся прямо в `products` и пересчитываются в той же транзакции после каждого создания/изменения/удаления отзыва. Строка товара блокируется (`SELECT ... FOR UPDATE`), поэтому значения остаются верными при одновременной записи; обновление выполняется native query, поэтому `version`/`updatedAt` товара не меняются |
| Удаление категории | Отклоняется (422), если есть дочерние категории или товары (включая архивные) |
| Наличие | `inStock` — копия остатка inventory-service (`available > 0`), обновляется событием `stock.level-changed`; события, пришедшие не по порядку, отбрасываются по `stock_updated_at`. Используется в ранжировании поиска и в бейдже «Нет в наличии» |
| Продажи | `salesCount` — число полученных покупок товара (`order.delivered`), используется для популярности в поиске и в сортировке `salesCount` |

Создание товара публикует событие `product.created` (exchange `shop.product.events`) после commit;
inventory-service создаёт для этого товара пустую запись об остатке.

## Запросы отзывов и напоминание

- Каждый отзыв хранит `verified_purchase` («Покупка подтверждена»): отзыв можно написать только о полученном товаре,
  поэтому флаг всегда `true` (существующие отзывы помечены миграцией `V7`); он возвращается во всех ответах с отзывами.
- **Ожидают отзыва** — товары из `product_purchases` покупателя без его отзыва, только `ACTIVE` (архивные и удалённые
  товары оценить нельзя). Товар, купленный несколько раз, показывается один раз — с последней доставкой.
- **Напоминание**: задача `ReviewReminderJob` (каждые `app.reviews.reminder.check-interval`, по умолчанию 15 мин)
  берёт заказы, доставленные не менее `app.reviews.reminder.delay-days` дней назад (по умолчанию **3**, переменная
  `REVIEW_REMINDER_DELAY_DAYS`) и ещё не обработанные. Для каждого заказа в отдельной транзакции строки
  `product_purchases` блокируются (`FOR UPDATE SKIP LOCKED` — два экземпляра не обработают заказ дважды), находятся
  товары без отзыва, публикуется событие `review.reminder-due` (exchange `shop.product.events`, ожидание publisher
  confirm), затем строки получают `review_reminder_processed_at`. Заказ обрабатывается **один раз**, даже если
  напоминать не о чем; повторно напоминание по нему не отправляется. Ошибка брокера откатывает транзакцию — заказ
  будет обработан следующим запуском. `eventId` выводится из id заказа (`UUID.nameUUIDFromBytes("review-reminder:<orderId>")`),
  поэтому повторная публикация (сбой commit после отправки) распознаётся notification-service как то же событие.
  Покупки, записанные до миграции `V7`, помечены обработанными: первый запуск не рассылает письма всем прежним покупателям.

| Событие | Exchange / routing key | Содержимое |
|---|---|---|
| `ReviewReminderDueEvent` | `shop.product.events` / `review.reminder-due` | `eventId, occurredAt, userId, orderId, orderNumber, items[{productId, productName, productSlug}]` — только товары, ещё не оценённые и `ACTIVE`; персональных данных (e-mail) нет |

## Потребляемые события

| Очередь | Exchange / routing key | Обработка |
|---|---|---|
| `product.order-delivered` (DLQ `product.order-delivered.dlq` через `shop.dlx`) | `shop.order.events` / `order.delivered` | Записывает `product_purchases(user_id, product_id, order_id, order_number, purchased_at)` для каждого товара заказа и увеличивает `sales_count` на 1 за каждую новую запись. Идемпотентно: unique key `(user_id, product_id, order_id)` + `INSERT ... ON CONFLICT DO NOTHING`, повторное событие ничего не добавляет. Несуществующие id товаров пропускаются (с предупреждением в логе) |
| `product.stock-level-changed` (DLQ `product.stock-level-changed.dlq`) | `shop.inventory.events` / `stock.level-changed` | `UPDATE products SET in_stock = available > 0, stock_updated_at = occurredAt WHERE stock_updated_at IS NULL OR stock_updated_at <= occurredAt` — устаревшее событие ничего не меняет |

## API

| Method | Путь | Права | Описание |
|---|---|---|---|
| GET | `/api/products?keyword=&categoryId=&brand=&minPrice=&maxPrice=&page=&size=&sort=` | публично | Поиск (см. раздел «Поиск»); `keyword` ≤ 100 символов (иначе 400). Сортировка по `relevance`, `price`, `name`, `createdAt`, `updatedAt`, `sku`, `ratingAverage`, `salesCount`; по умолчанию `relevance` с `keyword` и `createdAt,desc` без него (`relevance` без `keyword` = `salesCount,desc`). Ответ: поля страницы + `keyword`, `correctedKeyword`, `correctionApplied`, `facets` |
| GET | `/api/products/suggest?q=` | публично | Подсказки при вводе: ≤ 5 товаров (`id, name, slug, brand, thumbnailUrl, price, compareAtPrice, currency, inStock`), ≤ 3 категорий и брендов с числом совпадений, ≤ 5 продолжений запроса, `totalProducts`, `correctedQuery`. Пустой `q` → пустые подсказки, > 100 символов → 400. Кэш Caffeine 60 с; в gateway отдельный rate limit |
| GET | `/api/products/search/popular` | публично | `{queries: ≤10 частых запросов с результатами за 30 дней, categories: ≤6 самых продаваемых категорий с числом активных товаров}`, кэш 5 мин |
| GET | `/api/products?status=DRAFT` | ADMIN | Фильтр по статусу (игнорируется для не-администраторов) |
| GET | `/api/products/{id}` | публично | Подробности (с изображениями, категорией) |
| GET | `/api/products/slug/{slug}` | публично | Подробности по slug (для URL фронтенда) |
| GET | `/api/products/by-ids?ids=1,2,3` | публично | Не более 100 id, порядок сохраняется, несуществующие/скрытые id пропускаются — cart/order используют для получения текущих цен |
| POST | `/api/products` | ADMIN | Создание |
| PUT | `/api/products/{id}` | ADMIN | Обновление (нужно передать текущий SKU) |
| PATCH | `/api/products/{id}/status` | ADMIN | `{"status":"ACTIVE"}` |
| DELETE | `/api/products/{id}` | ADMIN | Архивирование |
| GET | `/api/categories` | публично | Дерево категорий |
| GET | `/api/categories/{id}`, `/api/categories/slug/{slug}` | публично | Подробности + дочерние категории |
| POST / PUT / DELETE | `/api/categories[/{id}]` | ADMIN | Управление категориями (PUT без `parentId` ⇒ категория становится корневой) |
| POST | `/api/products/images` | ADMIN | Загрузка изображения, `multipart/form-data` part `file` → 201 `{url, contentType, size}` |
| GET | `/api/products/images/{filename}` | публично | Скачивание загруженного изображения (правильный Content-Type даже при `Accept: application/json`, `Cache-Control: public, max-age=31536000, immutable`, `X-Content-Type-Options: nosniff`); 404, если нет |
| GET | `/api/products/{id}/reviews?page=0&size=10` | публично | Отзывы о товаре, сначала новые (`size` не более 50; сортировка `createdAt`, `updatedAt`, `rating`); 404, если товар не существует или не `ACTIVE` (администратор видит и скрытые товары) |
| GET | `/api/reviews/products/{id}/mine` | вход | `{purchased, canReview, purchasedAt, review}`; `purchasedAt` — последняя доставка товара покупателю, `review` равен `null`, если отзыв ещё не написан |
| PUT | `/api/reviews/products/{id}` | вход | Создание/изменение своего отзыва `{rating, comment, authorName}`; товар не получен → 422 `review.error.not-purchased` |
| DELETE | `/api/reviews/products/{id}` | вход | Удаление своего отзыва; 404, если его нет |
| GET | `/api/reviews/pending?page=&size=` | вход | Полученные и ещё не оценённые товары вызывающего, сначала недавно доставленные: `{productId, slug, name, imageUrl, orderId, deliveredAt}`. Порядок фиксирован (параметр `sort` игнорируется), `size` не более 50 |
| GET | `/api/reviews/pending/count?orderIds=a,b` | вход | `{count, byOrder}`: всего товаров без отзыва и (для переданных заказов, не более 50) число товаров каждого заказа без отзыва; чужие и полностью оценённые заказы в `byOrder` отсутствуют |
| GET | `/api/reviews/mine?page=&size=&sort=` | вход | Свои отзывы с товаром: `{id, productId, productSlug, productName, productImageUrl, status, rating, comment, authorName, verifiedPurchase, createdAt, updatedAt}`; `status` = `PUBLISHED` или `HIDDEN` (товар снят с продажи — отзыв не показывается, его можно только удалить). По умолчанию `updatedAt,desc`, сортировка `createdAt`, `updatedAt`, `rating` |
| GET | `/api/reviews/status?productIds=1,2,3` | вход | Для каждого товара одним запросом: `{productId, purchased, canReview, reviewed, rating}` в порядке запроса (повторы убираются). От 1 до 50 id, иначе 400 `review.error.no-products` / `review.error.too-many-products` |
| GET | `/api/reviews/admin?productId=&rating=&page=&size=` | ADMIN | Все отзывы (с `productName`, `productSlug`, `userId`, `verifiedPurchase`), сначала новые |
| DELETE | `/api/reviews/admin/{reviewId}` | ADMIN | Удаление любого отзыва (с пересчётом оценки) |
| GET | `/api/search/admin/stats?days=30&limit=20` | ADMIN | Статистика поиска за `days` (1–365) дней: всего запросов, уникальных, без результатов (+ доля), топ запросов (`searches`, `averageResults`, `lastSearchedAt`), запросы без результатов; `limit` 1–100 |
| GET | `/api/search/admin/synonyms?keyword=&page=&size=` | ADMIN | Группы синонимов (`id, terms, createdAt, updatedAt`), сначала изменённые недавно; `keyword` — часть слова |
| POST / PUT / DELETE | `/api/search/admin/synonyms[/{id}]` | ADMIN | `{"terms": [...]}`: 2–20 слов (буквы, цифры, пробел, дефис, ≤ 60 символов), приводятся к нижнему регистру, повторы удаляются; слово из другой группы → 409; неизвестная группа → 404. Изменения сразу используются поиском |

Примеры:

```bash
curl -s "http://localhost:8080/api/products?categoryId=4&sort=price,asc&size=5"
curl -s -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer <adminAccessToken>" -H "Content-Type: application/json" \
  -d '{"sku":"ACC-USB-HUB","name":"USB-C хаб 7 в 1","brand":"UGREEN","price":2490,
       "categoryId":11,"status":"ACTIVE","images":[{"url":"https://picsum.photos/seed/hub/800/800"}]}'
```

Загрузите изображение и используйте возвращённый `url` в `images` товара:

```bash
curl -s -X POST http://localhost:8080/api/products/images \
  -H "Authorization: Bearer <adminAccessToken>" -F "file=@phone.png"
# {"success":true,"message":"Изображение загружено","data":{"url":"/api/products/images/<uuid>.png","contentType":"image/png","size":48213}}
```

## Поиск

Поиск работает целиком в PostgreSQL (`V6__smart_search.sql`), без отдельного поискового движка:

| Часть | Реализация |
|---|---|
| Документ | `products.search_vector` (tsvector), заполняется триггером при изменении товара и при переименовании/переносе категории: название (вес A, морфология `shop_russian` = russian + unaccent; SKU без морфологии), бренд и названия категории/родительской категории (B), краткое и полное описание (C). GIN-индекс |
| Морфология | `websearch_to_tsquery('shop_russian', …)`: «телефоны», «наушников» находят «телефон», «наушники». Последнее слово запроса ищется и как префикс (`слово:*`), поэтому «клавиат» уже находит клавиатуры |
| Синонимы | Таблицы `search_synonyms` / `search_synonym_terms` (19 групп из миграции, управление в админке). Каждое слово запроса заменяется на «слово ИЛИ синонимы» (сравнение в т. ч. после стемминга), многословный синоним — для всего запроса |
| Раскладка | Если ничего не найдено, запрос, набранный в другой раскладке, исправляется: «ntktajy» → «телефон», «fqajy» → «айфон» |
| Опечатки | Затем неизвестные слова заменяются ближайшим словом словаря каталога (`search_words`, materialized view слов из названий, брендов и категорий; pg_trgm `similarity ≥ 0.4`, в т. ч. через транслитерацию): «самсунк» → «samsung». Исправление возвращается в `correctedKeyword` («Возможно, вы имели в виду …»), `correctionApplied=true` |
| Нечёткий поиск | Если найдено меньше 3 товаров, добавляются товары, у которых название или бренд похожи на запрос (`word_similarity ≥ 0.45`, GIN trigram-индексы на `shop_normalize(name/brand)`) |
| Ранжирование | `(ts_rank_cd + 0.5·similarity + 0.02·ln(1+sales_count) + 0.01·(rating/5)·ln(1+rating_count)) × (1, если в наличии, иначе 0.6)`, при равенстве — новые товары выше |
| Фасеты | Категории, бренды (без учёта регистра) и диапазоны цен (до 5 000, 5–15, 15–30, 30–60, 60–100 тыс., от 100 000 ₽) с количеством, плюс `minPrice`/`maxPrice`. Каждый фасет не учитывает свой фильтр (можно переключаться между брендами) |
| История | Первая страница поиска покупателя записывается в `search_queries` (нормализованный запрос, исправление, число результатов, время) в отдельной транзакции; ошибка записи не ломает поиск. Записи старше `app.search.history-retention` (180 дней) удаляются ежедневно в 03:30 |
| Обновление | После commit изменений товаров, категорий, синонимов, остатков и продаж кэши подсказок и популярных запросов очищаются, а словарь `search_words` перестраивается (`REFRESH MATERIALIZED VIEW CONCURRENTLY`) не чаще раза в 30 с |
| Безопасность | Все значения запроса передаются в SQL только как параметры; в `to_tsquery` попадают лишь слова из букв и цифр. Запрос нормализуется (нижний регистр, «ё» → «е», не более 8 слов, ≤ 100 символов) |

Параметры `app.search.*` (`config-repo/product-service.yml`): `fallback-below-results`, `word-similarity-threshold`,
`spelling-similarity-threshold`, `suggest-cache-ttl`, `suggest-cache-size`, `popular-cache-ttl`, `popular-period`,
`history-retention` (`SEARCH_HISTORY_RETENTION`), `history-cleanup-cron`, `dictionary-refresh-delay`.

```bash
curl -s "http://localhost:8080/api/products?keyword=самсунк" | jq '.data | {correctedKeyword, totalElements}'
curl -s "http://localhost:8080/api/products/suggest?q=ntktajy" | jq '.data.correctedQuery'
```

## Загрузка изображений

- Принимаются только JPEG, PNG, WebP, GIF, тип определяется по **magic bytes** (`Content-Type` и расширению файла не доверяем):
  текстовый файл, переименованный в `.png` → 400 `image.error.unsupported-type`; пустой файл или отсутствие part `file` → 400 `image.error.empty`.
- Лимит `app.catalog.image-max-size` (по умолчанию 5 МБ) → 400 `image.error.too-large`. Лимит multipart в Spring
  (`spring.servlet.multipart.max-file-size`/`max-request-size`, по умолчанию 6 МБ) немного выше; его превышение
  (`MaxUploadSizeExceededException`) тоже возвращает `ErrorResponse` 400 с тем же сообщением.
- Сохраняется как `<случайный UUID>.<расширение по фактическому типу>` в `app.catalog.image-storage-dir`
  (`PRODUCT_IMAGE_DIR`, по умолчанию `./data/product-images`; каталог создаётся при запуске, если его нет).
  В Docker это named volume, подключённый в `/data/product-images` (Dockerfile заранее создаёт каталог с владельцем `app:app`).
- При скачивании имя файла строго проверяется регулярным выражением (UUID + допустимое расширение), поэтому path traversal невозможен.
- **Файлы не удаляются**: изображения, которые больше не использует ни один товар (замена изображения, пробная загрузка),
  остаются в volume (файлы-сироты). Это допустимо; при необходимости очищайте вручную.

## Сообщения

`src/main/resources/messages_ru.properties` (на русском, UTF-8) содержит, в частности, ключи: `review.saved`, `review.deleted`,
`review.error.not-purchased`, `review.error.not-found`, `review.error.product-not-found`,
`review.error.unsupported-sort`, `review.error.no-products`, `review.error.too-many-products`,
`review.error.too-many-orders`, `image.uploaded`, `image.error.empty`, `image.error.unsupported-type`,
`image.error.too-large`, `image.error.not-found`, `product.error.keyword-too-long`, `search.synonym-created|updated|deleted`,
`search.error.synonym-not-found`, `search.error.synonym-too-few`, `search.error.synonym-term-taken`,
`search.error.unsupported-sort`, `search.error.stats-days`, `search.error.stats-limit`, `validation.synonym-term`. Сообщения переводятся через `MessageSource` с локалью по умолчанию
ru-RU; все сообщения API, включая общие из `common-messages_ru.properties` (common-lib) и сообщения Bean Validation, — на русском.

## Начальные данные (Flyway)

- `V1__init_schema.sql`: `categories`, `products`, `product_images` (CHECK для цены, статуса; unique SKU/slug)
- `V2__seed_catalog.sql`: 4 корневые категории, 8 дочерних, **24 товара** (22 `ACTIVE`, 1 `DRAFT` id 23,
  1 `ARCHIVED` id 24), у каждого товара 2 изображения-заглушки с picsum.photos. Id фиксированы, потому что inventory-service
  заполняет остатки для тех же id.
- `V3__prices_in_rub.sql`: перевод демонстрационных цен в RUB
- `V4__reviews.sql`: `product_reviews` (unique `(product_id, user_id)`, CHECK rating 1–5, каскадное удаление по товару),
  `product_purchases` (unique `(user_id, product_id, order_id)`), столбцы `rating_average NUMERIC(2,1)` и
  `rating_count INT` в `products`
- Следующая миграция переводит демонстрационный каталог на русский: названия и описания категорий и товаров на русском,
  slug — латинская транслитерация кириллических названий (id и SKU не меняются)
- `V6__smart_search.sql`: расширения `pg_trgm`, `unaccent`; конфигурации `shop_russian`, `shop_simple`; функция
  `shop_normalize`; столбцы `search_vector`, `in_stock`, `stock_updated_at`, `sales_count` (заполняются для существующих
  товаров) с триггерами и индексами; таблицы `search_synonyms` + `search_synonym_terms` (19 групп),
  `search_queries`; materialized view `search_words`. Начальные остатки приходят от inventory-service (его `V5`)
- `V7__review_requests.sql`: `product_reviews.verified_purchase`, `product_purchases.order_number` и
  `review_reminder_processed_at` (существующие покупки помечаются обработанными), индексы для «ожидают отзыва»,
  «мои отзывы» и выборки заказов, по которым пора напомнить

## Переменные окружения

`CONFIG_SERVER_*`, `EUREKA_*`, `RABBITMQ_*`, `JWT_SECRET`, `PRODUCT_DB_PASSWORD`;
необязательные: `CATALOG_CURRENCY` (по умолчанию `RUB`), `PRODUCT_SERVICE_PORT`, `PRODUCT_IMAGE_DIR`
(по умолчанию `./data/product-images`, Docker: `/data/product-images`), `PRODUCT_IMAGE_MAX_SIZE` (по умолчанию `5MB`),
`PRODUCT_IMAGE_MULTIPART_MAX_SIZE` (по умолчанию `6MB`, должен быть немного больше `PRODUCT_IMAGE_MAX_SIZE`),
`SEARCH_HISTORY_RETENTION` (по умолчанию `180d`), `REVIEW_REMINDER_DELAY_DAYS` (дней после доставки до письма-напоминания
об отзыве, по умолчанию `3`), `REVIEW_REMINDER_ENABLED` (`false` отключает задачу напоминаний). Публикация напоминаний
требует `spring.rabbitmq.publisher-confirm-type: simple` (задано в `application.yml`).

## Тесты

```bash
cd backend
./mvnw -pl product-service test
```

- Unit-тесты: `ProductServiceImplTest`, `CategoryServiceImplTest`, `SlugUtilsTest`, `ReviewServiceImplTest`
  (проверка покупки, создание/изменение, пересчёт оценки, удаление, удаление администратором), `PurchaseServiceImplTest` (идемпотентность, пропуск неизвестных id),
  `LocalImageStorageServiceTest` (magic bytes, размер, имя файла, path traversal), `GlobalExceptionHandlerTest`,
  `SearchTextTest`, `KeyboardLayoutTest`, `TransliterationTest`, `SynonymServiceImplTest`, `StockLevelServiceImplTest`,
  `SearchHistoryServiceImplTest`
- Testcontainers: `ProductCatalogIntegrationTest` (настоящие PostgreSQL + RabbitMQ, демонстрационные данные, событие `product.created`),
  `ReviewsAndImagesIntegrationTest` (`order.delivered` через RabbitMQ → отзыв → средняя оценка, сортировка по оценке,
  не купивший пользователь получает 422, модерация; загрузка PNG и повторное скачивание, отказ для текстового файла, переименованного в `.png`),
  `ReviewRequestsIntegrationTest` (ожидающие отзыва товары, счётчики по заказам, состояние нескольких товаров, «мои отзывы»,
  `verifiedPurchase`, снятые с продажи товары; пользователь A не видит данных пользователя B; проверка параметров;
  одно напоминание на заказ только о неоценённых товарах, детерминированный `eventId`, недоставленные вовремя заказы ждут),
  `SearchIntegrationTest` (морфология, префикс, опечатка «самсунк», раскладка «ntktajy», синонимы, ранжирование по
  продажам и наличию, SQL-инъекции и синтаксис запросов, фасеты, подсказки, журнал и статистика, управление синонимами,
  события `stock.level-changed` не по порядку)
