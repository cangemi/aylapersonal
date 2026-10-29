ALTER TABLE auth_accounts
 ADD COLUMN login_failed_attempts integer NOT NULL DEFAULT 0 CHECK(login_failed_attempts>=0),
 ADD COLUMN login_lock_level smallint NOT NULL DEFAULT 0 CHECK(login_lock_level BETWEEN 0 AND 2),
 ADD COLUMN login_locked_until timestamptz,
 ADD COLUMN last_login_failure_at timestamptz;
