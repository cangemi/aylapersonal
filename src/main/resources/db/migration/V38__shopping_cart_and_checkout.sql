ALTER TABLE shopping_items DROP CONSTRAINT shopping_items_status_check;
ALTER TABLE shopping_items ADD CONSTRAINT shopping_items_status_check CHECK(status IN ('PENDING','IN_CART','PURCHASED'));
DROP INDEX shopping_pending_name;
CREATE UNIQUE INDEX shopping_pending_name ON shopping_items(user_id,name_key) WHERE status IN ('PENDING','IN_CART');
ALTER TABLE shopping_items ADD COLUMN unit_price_cents bigint CHECK(unit_price_cents BETWEEN 0 AND 9007199254740991),
 ADD COLUMN total_price_cents bigint CHECK(total_price_cents BETWEEN 0 AND 9007199254740991),
 ADD COLUMN checkout_id uuid;
CREATE TABLE shopping_checkouts (
 id uuid PRIMARY KEY, user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 total_cents bigint NOT NULL CHECK(total_cents BETWEEN 0 AND 9007199254740991),
 financial_entry_id uuid REFERENCES financial_entries(id) ON DELETE SET NULL,
 agent_turn_id uuid REFERENCES finance_agent_turns(id) ON DELETE SET NULL,
 paid_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shopping_checkouts_user ON shopping_checkouts(user_id,paid_at DESC);
CREATE UNIQUE INDEX shopping_checkouts_turn ON shopping_checkouts(agent_turn_id) WHERE agent_turn_id IS NOT NULL;
ALTER TABLE shopping_items ADD CONSTRAINT shopping_checkout_fk FOREIGN KEY(checkout_id) REFERENCES shopping_checkouts(id);
