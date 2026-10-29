CREATE TABLE application_events (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    user_id varchar(255),
    source varchar(16) NOT NULL,
    method varchar(8) NOT NULL,
    route varchar(240) NOT NULL,
    action varchar(180) NOT NULL,
    status smallint NOT NULL,
    duration_ms bigint NOT NULL CHECK(duration_ms>=0),
    error_type varchar(120),
    error_reason varchar(240),
    stack_summary text
);
CREATE INDEX application_events_time ON application_events(occurred_at DESC,id);
CREATE INDEX application_events_user_time ON application_events(user_id,occurred_at DESC);
CREATE INDEX application_events_errors ON application_events(occurred_at DESC) WHERE status>=400;
