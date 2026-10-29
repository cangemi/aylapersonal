-- Taxa CDI diária publicada pelo Banco Central (série SGS 12), guardada como cache local.
CREATE TABLE cdi_rates (
    rate_date date PRIMARY KEY,
    daily_percent numeric(14,8) NOT NULL CHECK (daily_percent >= 0)
);

-- Aportes e resgates vêm dos lançamentos financeiros da categoria Investimento;
-- aqui ficam apenas as preferências do produto (inicialmente a Caixinha Nubank a 100% do CDI).
CREATE TABLE investment_settings (
    user_id varchar(255) PRIMARY KEY REFERENCES chat_users(user_id),
    cdi_percent numeric(7,2) NOT NULL DEFAULT 100 CHECK (cdi_percent > 0 AND cdi_percent <= 1000),
    updated_at timestamptz NOT NULL DEFAULT now()
);
