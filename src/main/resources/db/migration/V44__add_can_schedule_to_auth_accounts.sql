ALTER TABLE auth_accounts
    ADD COLUMN can_schedule boolean NOT NULL DEFAULT true
        CHECK (can_schedule IN (true, false));