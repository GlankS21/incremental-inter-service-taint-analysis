-- The opening balances of the seed data are shown to administrators in the stock ledger: Russian text
UPDATE stock_movements
SET reason = 'Начальный остаток'
WHERE reference = 'seed'
  AND reason = 'Opening balance';
