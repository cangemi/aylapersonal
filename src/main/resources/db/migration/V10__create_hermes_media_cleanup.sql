CREATE TABLE hermes_media_cleanup (
    session_id varchar(64) PRIMARY KEY,
    profile varchar(64) NOT NULL,
    cache_names text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
