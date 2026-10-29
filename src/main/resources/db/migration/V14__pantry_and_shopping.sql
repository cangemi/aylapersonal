CREATE TABLE pantry_items (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    name varchar(160) NOT NULL,
    name_key varchar(160) NOT NULL,
    quantity numeric(15,3) NOT NULL CHECK(quantity>=0),
    unit varchar(10) NOT NULL CHECK(unit IN ('UN','KG','G','L','ML','PACOTE')),
    minimum_quantity numeric(15,3) NOT NULL DEFAULT 0 CHECK(minimum_quantity>=0),
    expires_on date,
    category varchar(80) NOT NULL DEFAULT '',
    notes varchar(500) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id,name_key)
);
CREATE TABLE shopping_items (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    name varchar(160) NOT NULL,
    name_key varchar(160) NOT NULL,
    quantity numeric(15,3),
    unit varchar(10) CHECK(unit IN ('UN','KG','G','L','ML','PACOTE')),
    category varchar(80) NOT NULL DEFAULT '',
    notes varchar(500) NOT NULL DEFAULT '',
    status varchar(15) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PURCHASED')),
    stock_applied boolean NOT NULL DEFAULT false,
    purchased_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((quantity IS NULL AND unit IS NULL) OR (quantity>0 AND unit IS NOT NULL))
);
CREATE UNIQUE INDEX shopping_pending_name ON shopping_items(user_id,name_key) WHERE status='PENDING';
CREATE INDEX pantry_owner ON pantry_items(user_id,name_key);
CREATE INDEX shopping_owner ON shopping_items(user_id,status,updated_at DESC);
CREATE TABLE household_receipts (
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    request_key varchar(200) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    result_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,request_key)
);
