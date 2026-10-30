ALTER TABLE auth_accounts
    ADD COLUMN pantry_view varchar(16) NOT NULL DEFAULT 'GRID'
        CHECK (pantry_view IN ('GRID', 'LIST', 'COMPACT', 'TABLE'));
