CREATE TABLE ai_usage_events (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    user_id varchar(255),
    source varchar(16) NOT NULL,
    provider varchar(160),
    model varchar(160),
    purpose varchar(16) NOT NULL,
    status smallint NOT NULL,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    input_tokens bigint CHECK (input_tokens >= 0),
    output_tokens bigint CHECK (output_tokens >= 0),
    total_tokens bigint CHECK (total_tokens >= 0)
);
CREATE INDEX ai_usage_time ON ai_usage_events(occurred_at DESC);
CREATE INDEX ai_usage_user_time ON ai_usage_events(user_id, occurred_at DESC);
