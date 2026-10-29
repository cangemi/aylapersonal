-- Saldo de partida: permite alinhar a caixinha ao saldo real do Nubank sem lançar o histórico inteiro.
-- Lançamentos de investimento até a data do saldo são ignorados; os posteriores continuam somando.
ALTER TABLE investment_settings
    ADD COLUMN baseline_date date,
    ADD COLUMN baseline_balance_cents bigint CHECK (baseline_balance_cents IS NULL OR baseline_balance_cents > 0),
    ADD COLUMN baseline_invested_cents bigint CHECK (baseline_invested_cents IS NULL OR baseline_invested_cents >= 0),
    ADD CONSTRAINT investment_baseline_complete CHECK (
        (baseline_date IS NULL AND baseline_balance_cents IS NULL AND baseline_invested_cents IS NULL)
        OR (baseline_date IS NOT NULL AND baseline_balance_cents IS NOT NULL AND baseline_invested_cents IS NOT NULL));
