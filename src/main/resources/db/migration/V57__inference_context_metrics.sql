-- Per-user inference observations. Never store prompt content, tool arguments or credentials.
CREATE TABLE provider_inference_events (
    id varchar(128) PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
    tenant_id varchar(255), -- Set only from a future authenticated business context; not inferred from chat.
    profile varchar(64) NOT NULL,
    provider varchar(40) NOT NULL,
    model varchar(255),
    session_id varchar(128),
    request_id varchar(64),
    source varchar(16) NOT NULL,
    kind varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL,
    metrics_json text NOT NULL
);
CREATE INDEX provider_inference_events_user_time ON provider_inference_events(user_id,occurred_at DESC);
CREATE INDEX provider_inference_events_time ON provider_inference_events(occurred_at DESC);
CREATE INDEX provider_inference_events_request ON provider_inference_events(request_id) WHERE request_id IS NOT NULL;
