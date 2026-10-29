ALTER TABLE shopping_items DROP CONSTRAINT shopping_items_status_check;
ALTER TABLE shopping_items ADD CONSTRAINT shopping_items_status_check CHECK(status IN ('PENDING','IN_CART','PURCHASED','ARCHIVED'));
CREATE TABLE shopping_purchase_items (
 id uuid PRIMARY KEY, checkout_id uuid NOT NULL REFERENCES shopping_checkouts(id) ON DELETE CASCADE,
 source_item_id uuid REFERENCES shopping_items(id) ON DELETE SET NULL,
 name varchar(160) NOT NULL, name_key varchar(160) NOT NULL, quantity numeric(12,3), unit varchar(10),
 unit_price_cents bigint CHECK(unit_price_cents BETWEEN 0 AND 9007199254740991),
 total_price_cents bigint CHECK(total_price_cents BETWEEN 0 AND 9007199254740991), purchased boolean NOT NULL,
 CHECK(NOT purchased OR (quantity IS NOT NULL AND quantity>0 AND unit IS NOT NULL AND total_price_cents IS NOT NULL))
);
CREATE INDEX shopping_purchase_items_checkout ON shopping_purchase_items(checkout_id);
CREATE INDEX shopping_purchase_items_product ON shopping_purchase_items(name_key,unit,checkout_id) WHERE purchased;
INSERT INTO shopping_purchase_items(id,checkout_id,source_item_id,name,name_key,quantity,unit,unit_price_cents,total_price_cents,purchased)
 SELECT gen_random_uuid(),s.checkout_id,s.id,s.name,s.name_key,s.quantity,s.unit,s.unit_price_cents,s.total_price_cents,true
 FROM shopping_items s JOIN shopping_checkouts c ON c.id=s.checkout_id AND c.user_id=s.user_id
 WHERE s.status='PURCHASED' AND s.quantity>0 AND s.unit IS NOT NULL AND s.total_price_cents IS NOT NULL;
