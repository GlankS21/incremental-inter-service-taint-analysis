-- The shop now sells in Russia: the sample catalogue of V2 is translated into Russian.
-- Rows are matched by their fixed ids (and products also by SKU), which inventory-service and past
-- orders refer to; ids, SKUs and prices (converted to RUB by V3) are kept.
-- Slugs are the transliteration of the new names by SlugUtils (ICAO Doc 9303 table, lower case,
-- words separated by dashes); none of them collides with an old slug, so the unique constraints hold
-- row by row.

UPDATE categories c
SET name        = t.name,
    slug        = t.slug,
    description = t.description,
    updated_at  = now()
FROM (VALUES (1, 'Телефоны и планшеты', 'telefony-i-planshety', 'Смартфоны и планшеты'),
             (4, 'Ноутбуки', 'noutbuki', 'Ноутбуки для работы, учёбы и игр'),
             (7, 'Аудиотехника', 'audiotekhnika', 'Наушники и портативные колонки'),
             (10, 'Аксессуары', 'aksessuary', 'Зарядные устройства, кабели, мыши и клавиатуры'),
             (2, 'Смартфоны', 'smartfony', NULL),
             (3, 'Планшеты', 'planshety', NULL),
             (5, 'Офисные ноутбуки', 'ofisnye-noutbuki', NULL),
             (6, 'Игровые ноутбуки', 'igrovye-noutbuki', NULL),
             (8, 'Наушники', 'naushniki', NULL),
             (9, 'Портативные колонки', 'portativnye-kolonki', NULL),
             (11, 'Зарядные устройства и кабели', 'zariadnye-ustroistva-i-kabeli', NULL),
             (12, 'Мыши и клавиатуры', 'myshi-i-klaviatury', NULL))
         AS t(id, name, slug, description)
WHERE c.id = t.id;

-- Data-modifying CTEs share one snapshot: the image update still sees the old product names, so it
-- translates only the alt texts that V2 copied from the product name.
WITH t(id, sku, name, slug, short_description, description) AS (
    VALUES (1, 'PHN-IP15-128', 'Смартфон Apple iPhone 15 128 ГБ', 'smartfon-apple-iphone-15-128-gb',
            'Чип A16 Bionic, камера 48 Мп, Dynamic Island',
            'Дисплей Super Retina XDR 6,1 дюйма, разъём USB-C, аккумулятора хватает на весь день.'),
           (2, 'PHN-S24-256', 'Смартфон Samsung Galaxy S24 256 ГБ', 'smartfon-samsung-galaxy-s24-256-gb',
            'Galaxy AI, экран 120 Гц, камера 50 Мп',
            'Дисплей Dynamic AMOLED 2X 6,2 дюйма, процессор Exynos 2400, 8 ГБ оперативной памяти.'),
           (3, 'PHN-PX8-128', 'Смартфон Google Pixel 8 128 ГБ', 'smartfon-google-pixel-8-128-gb',
            'Tensor G3, обработка снимков с помощью ИИ, 7 лет обновлений',
            'Дисплей Actua 6,2 дюйма с частотой 120 Гц, защита от воды IP68.'),
           (4, 'PHN-RN13-128', 'Смартфон Xiaomi Redmi Note 13 128 ГБ', 'smartfon-xiaomi-redmi-note-13-128-gb',
            'Экран AMOLED 120 Гц, быстрая зарядка 33 Вт',
            'Камера 108 Мп, аккумулятор 5000 мА·ч, 6 ГБ оперативной памяти.'),
           (5, 'TAB-IPAD10-64', 'Планшет Apple iPad 10-го поколения Wi-Fi 64 ГБ',
            'planshet-apple-ipad-10-go-pokoleniia-wi-fi-64-gb',
            'Дисплей Liquid Retina 10,9 дюйма, чип A14',
            'Поддержка Apple Pencil (USB-C) и клавиатуры Magic Keyboard Folio.'),
           (6, 'TAB-TABS9-128', 'Планшет Samsung Galaxy Tab S9 128 ГБ', 'planshet-samsung-galaxy-tab-s9-128-gb',
            'Экран Dynamic AMOLED 11 дюймов, стилус S Pen в комплекте',
            'Процессор Snapdragon 8 Gen 2, защита от воды IP68.'),
           (7, 'LAP-MBA-M2', 'Ноутбук Apple MacBook Air 13 M2 8 ГБ/256 ГБ', 'noutbuk-apple-macbook-air-13-m2-8-gb-256-gb',
            'Чип M2, лёгкий корпус весом 1,24 кг, до 18 часов работы',
            'Дисплей Liquid Retina 13,6 дюйма, бесшумная конструкция без вентилятора, зарядка MagSafe.'),
           (8, 'LAP-XPS13-I7', 'Ноутбук Dell XPS 13 i7/16 ГБ/512 ГБ', 'noutbuk-dell-xps-13-i7-16-gb-512-gb',
            'Цельный алюминиевый корпус, экран InfinityEdge',
            'Процессор Intel Core i7 13-го поколения, SSD 512 ГБ, Windows 11.'),
           (9, 'LAP-TPE14-I5', 'Ноутбук Lenovo ThinkPad E14 i5/16 ГБ/512 ГБ', 'noutbuk-lenovo-thinkpad-e14-i5-16-gb-512-gb',
            'Фирменная клавиатура ThinkPad, надёжность по военному стандарту',
            'Процессор Intel Core i5, экран 14 дюймов WUXGA.'),
           (10, 'LAP-ROG-G14', 'Игровой ноутбук ASUS ROG Zephyrus G14 RTX 4060',
            'igrovoi-noutbuk-asus-rog-zephyrus-g14-rtx-4060',
            'Ryzen 9, RTX 4060, OLED-экран 120 Гц',
            'Компактный игровой ноутбук с экраном 14 дюймов, 16 ГБ оперативной памяти, SSD 1 ТБ.'),
           (11, 'LAP-LEG5-4060', 'Игровой ноутбук Lenovo Legion 5 RTX 4060', 'igrovoi-noutbuk-lenovo-legion-5-rtx-4060',
            'Ryzen 7, RTX 4060, экран 165 Гц',
            'Система охлаждения Legion Coldfront, клавиатура с RGB-подсветкой.'),
           (12, 'LAP-NITRO5-3050', 'Игровой ноутбук Acer Nitro 5 RTX 3050', 'igrovoi-noutbuk-acer-nitro-5-rtx-3050',
            'Core i5, RTX 3050, экран 144 Гц',
            'Доступный игровой ноутбук для учёбы и развлечений.'),
           (13, 'AUD-APP2-USBC', 'Наушники Apple AirPods Pro 2 USB-C', 'naushniki-apple-airpods-pro-2-usb-c',
            'Активное шумоподавление, пространственное аудио',
            'Зарядный футляр с разъёмом USB-C, защита от воды IP54.'),
           (14, 'AUD-WH1000XM5', 'Наушники Sony WH-1000XM5', 'naushniki-sony-wh-1000xm5',
            'Полноразмерные наушники с одним из лучших шумоподавлений',
            'До 30 часов работы, быстрая зарядка, подключение к нескольким устройствам.'),
           (15, 'AUD-BUDS2PRO', 'Наушники Samsung Galaxy Buds2 Pro', 'naushniki-samsung-galaxy-buds2-pro',
            'Звук Hi-Fi 24 бит, активное шумоподавление',
            'Компактный корпус, защита от воды IPX7.'),
           (16, 'AUD-JBLFLIP6', 'Портативная колонка JBL Flip 6', 'portativnaia-kolonka-jbl-flip-6',
            'Bluetooth-колонка с защитой от воды IP67',
            'До 12 часов работы, объединение нескольких колонок через PartyBoost.'),
           (17, 'AUD-MARSHALL-EM2', 'Портативная колонка Marshall Emberton II',
            'portativnaia-kolonka-marshall-emberton-ii',
            'Объёмный звук на 360°, более 30 часов работы',
            'Классический дизайн, защита от воды IP67.'),
           (18, 'ACC-ANK-65W', 'Зарядное устройство Anker 65 Вт GaN, 3 порта',
            'zariadnoe-ustroistvo-anker-65-vt-gan-3-porta',
            'Быстрая зарядка ноутбука и телефона одновременно',
            'Два порта USB-C и один USB-A, компактный корпус благодаря технологии GaN.'),
           (19, 'ACC-UGR-C2C', 'Кабель UGREEN USB-C — USB-C 100 Вт, 2 м', 'kabel-ugreen-usb-c-usb-c-100-vt-2-m',
            'Зарядка мощностью до 100 Вт, нейлоновая оплётка',
            'Передача данных до 480 Мбит/с, выдерживает 10 000 изгибов.'),
           (20, 'ACC-MXM3S', 'Мышь Logitech MX Master 3S', 'mysh-logitech-mx-master-3s',
            'Беспроводная мышь премиум-класса с тихими кнопками',
            'Сенсор 8000 DPI, колесо прокрутки MagSpeed, подключение до трёх устройств.'),
           (21, 'ACC-K380', 'Клавиатура Logitech K380', 'klaviatura-logitech-k380',
            'Компактная Bluetooth-клавиатура для нескольких устройств',
            'До 24 месяцев работы от батареек, совместима с Windows, macOS и iPadOS.'),
           (22, 'ACC-KCK2', 'Механическая клавиатура Keychron K2 V2', 'mekhanicheskaia-klaviatura-keychron-k2-v2',
            'Беспроводная механическая клавиатура формата 75%, переключатели Gateron',
            'RGB-подсветка, поддержка macOS и Windows.'),
           -- Not visible to customers: DRAFT and ARCHIVED examples
           (23, 'PHN-IP16-128', 'Смартфон Apple iPhone 16 128 ГБ', 'smartfon-apple-iphone-16-128-gb',
            'Скоро в продаже', NULL),
           (24, 'AUD-APP1', 'Наушники Apple AirPods Pro 1-го поколения', 'naushniki-apple-airpods-pro-1-go-pokoleniia',
            'Сняты с продажи', NULL)),
     images AS (
         UPDATE product_images i
             SET alt_text = t.name
             FROM products p
                 JOIN t ON t.id = p.id AND t.sku = p.sku
             WHERE i.product_id = p.id
                 AND i.alt_text = p.name)
UPDATE products p
SET name              = t.name,
    slug              = t.slug,
    short_description = t.short_description,
    description       = t.description,
    updated_at        = now()
FROM t
WHERE p.id = t.id
  AND p.sku = t.sku;
