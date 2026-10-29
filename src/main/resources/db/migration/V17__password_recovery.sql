ALTER TABLE auth_accounts ADD COLUMN recovery_password_hash varchar(100);
ALTER TABLE auth_accounts ADD COLUMN recovery_expires_at timestamptz;
ALTER TABLE auth_accounts ADD COLUMN recovery_requested_at timestamptz;
CREATE TABLE auth_recovery_limits (
    bucket char(64) PRIMARY KEY,
    attempts integer NOT NULL DEFAULT 1,
    window_start timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE whatsapp_messages ADD COLUMN sensitive boolean NOT NULL DEFAULT false;
CREATE INDEX whatsapp_sensitive_messages ON whatsapp_messages(instance_id,chat_id) WHERE sensitive;
