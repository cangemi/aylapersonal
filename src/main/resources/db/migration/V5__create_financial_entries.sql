CREATE TABLE financial_entries (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    type varchar(10) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    description varchar(300) NOT NULL,
    category varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX financial_entries_user_date ON financial_entries(user_id, occurred_at DESC, id);
-- Receipts survive deletion: retrying a successful request cannot recreate a deleted expense.
CREATE TABLE financial_receipts (
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    request_key varchar(160) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    entry_id uuid NOT NULL,
    PRIMARY KEY(user_id, request_key)
);
