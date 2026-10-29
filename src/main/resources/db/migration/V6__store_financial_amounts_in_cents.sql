-- V5 amounts are exact NUMERIC(19,2). Convert existing rows once, without rounding.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM financial_entries WHERE amount * 100 > 9007199254740991) THEN
        RAISE EXCEPTION 'Lançamento fora do limite inteiro de centavos; conversão cancelada sem alterar dados';
    END IF;
END $$;
ALTER TABLE financial_entries RENAME COLUMN amount TO amount_cents;
ALTER TABLE financial_entries ALTER COLUMN amount_cents TYPE bigint USING (amount_cents * 100)::bigint;
ALTER TABLE financial_entries ADD CONSTRAINT financial_entries_amount_cents_max CHECK (amount_cents <= 9007199254740991);
