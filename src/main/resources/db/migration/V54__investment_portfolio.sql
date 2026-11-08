CREATE TABLE investment_positions (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    institution varchar(120) NOT NULL,
    asset_type varchar(32) NOT NULL,
    ticker varchar(32),
    opened_on date NOT NULL,
    opening_balance_cents bigint NOT NULL CHECK (opening_balance_cents >= 0),
    opening_invested_cents bigint NOT NULL CHECK (opening_invested_cents >= 0),
    rate_type varchar(16) NOT NULL CHECK (rate_type IN ('CDI','ANNUAL','MONTHLY','MANUAL')),
    rate_percent numeric(12,6) NOT NULL CHECK (rate_percent >= 0 AND rate_percent <= 1000),
    day_basis varchar(16) NOT NULL CHECK (day_basis IN ('BUSINESS','CALENDAR')),
    maturity_date date,
    notes varchar(2000) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0,
    request_key varchar(160),
    request_fingerprint varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX investment_positions_owner ON investment_positions(user_id, created_at);
CREATE UNIQUE INDEX investment_positions_request ON investment_positions(user_id, request_key) WHERE request_key IS NOT NULL;

CREATE TABLE investment_position_events (
    id uuid PRIMARY KEY,
    position_id uuid NOT NULL REFERENCES investment_positions(id) ON DELETE CASCADE,
    event_type varchar(16) NOT NULL CHECK (event_type IN ('DEPOSIT','WITHDRAWAL','INCOME','VALUATION','RATE_CHANGE')),
    occurred_on date NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    rate_type varchar(16),
    rate_percent numeric(12,6),
    day_basis varchar(16),
    notes varchar(500) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX investment_position_events_history ON investment_position_events(position_id, occurred_on, created_at, id);
