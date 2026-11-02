CREATE TABLE IF NOT EXISTS push_delivery_receipts (
    event_id VARCHAR(255) NOT NULL,
    endpoint VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, endpoint)
);
CREATE INDEX IF NOT EXISTS push_delivery_receipts_event_idx ON push_delivery_receipts(event_id);