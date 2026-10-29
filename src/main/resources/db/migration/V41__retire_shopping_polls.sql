-- Retire the poll workflow. Keep V40 immutable and retain old delivery records for audit.
UPDATE shopping_whatsapp_polls SET active=false,updated_at=now() WHERE active;
UPDATE shopping_poll_messages SET active=false WHERE active;
UPDATE shopping_poll_events SET processed_at=now() WHERE processed_at IS NULL;
