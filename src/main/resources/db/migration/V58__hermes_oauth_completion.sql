-- Make approved OAuth processing idempotent, including quota/account invalidation.
ALTER TABLE hermes_oauth_sessions ADD COLUMN completed_at timestamptz;
