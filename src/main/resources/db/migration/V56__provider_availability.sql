-- Credential scopes are opaque HMAC fingerprints supplied by the trusted Hermes observer.
-- No OAuth tokens, provider messages, prompts or account identifiers are stored here.
CREATE TABLE provider_connections (
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
    provider varchar(40) NOT NULL,
    profile varchar(64) NOT NULL,
    credential_scope varchar(64) NOT NULL,
    credential_observed_at timestamptz NOT NULL,
    last_model varchar(255),
    PRIMARY KEY(user_id,provider)
);
CREATE INDEX provider_connections_scope ON provider_connections(provider,credential_scope);
CREATE TABLE provider_observed_models (
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
    provider varchar(40) NOT NULL,
    model varchar(255) NOT NULL,
    PRIMARY KEY(user_id,provider,model)
);

CREATE TABLE provider_model_availability (
    provider varchar(40) NOT NULL,
    credential_scope varchar(64) NOT NULL,
    model varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    observed_at timestamptz NOT NULL,
    detected_at timestamptz,
    retry_after_seconds bigint,
    available_at timestamptz,
    retry_attempt_at timestamptz,
    alternates_json text NOT NULL DEFAULT '[]',
    PRIMARY KEY(provider,credential_scope,model)
);
CREATE TABLE provider_quota_snapshots (
    provider varchar(40) NOT NULL,
    credential_scope varchar(64) NOT NULL,
    observed_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    quotas_json text NOT NULL DEFAULT '[]',
    PRIMARY KEY(provider,credential_scope)
);
CREATE TABLE provider_telemetry_receipts (
    event_id varchar(128) PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE chat_jobs ADD COLUMN provider_notice_json text;
