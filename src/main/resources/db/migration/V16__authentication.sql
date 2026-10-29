CREATE TABLE auth_accounts (
    user_id varchar(255) PRIMARY KEY REFERENCES chat_users(user_id),
    username varchar(120) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    role varchar(10) NOT NULL CHECK(role IN ('OWNER','USER')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX auth_single_owner ON auth_accounts(role) WHERE role='OWNER';
CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES auth_accounts(user_id),
    access_hash char(64) NOT NULL UNIQUE,
    access_expires_at timestamptz NOT NULL,
    refresh_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_sessions_user ON auth_sessions(user_id);
CREATE TABLE auth_refresh_tokens (
    token_hash char(64) PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES auth_sessions(id),
    consumed_at timestamptz,
    expires_at timestamptz NOT NULL
);
CREATE INDEX auth_refresh_session ON auth_refresh_tokens(session_id);
CREATE TABLE auth_login_throttle (
    bucket varchar(70) PRIMARY KEY,
    failures integer NOT NULL DEFAULT 0,
    window_start timestamptz NOT NULL DEFAULT now(),
    blocked_until timestamptz
);
