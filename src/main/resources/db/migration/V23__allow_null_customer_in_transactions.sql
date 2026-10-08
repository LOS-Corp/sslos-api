-- Allow null customer_id on transactions for walk-in counter customers
ALTER TABLE transactions ALTER COLUMN customer_id DROP NOT NULL;
