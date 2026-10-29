ALTER TABLE auth_accounts ADD COLUMN onboarded boolean NOT NULL DEFAULT true;
ALTER TABLE auth_accounts ADD COLUMN password_temporary boolean NOT NULL DEFAULT false;
