-- Addresses saved without a country are in Russia (the entity and the API default to RU as well).
-- Existing rows are not changed.

ALTER TABLE addresses
    ALTER COLUMN country_code SET DEFAULT 'RU';
