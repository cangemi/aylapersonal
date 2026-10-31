-- Allow multiple Google accounts per user:
-- primary key becomes (user_id, connection_id), and (user_id, calendar_id)
-- identifies the same Google account so re-authorization updates instead of duplicating.

ALTER TABLE calendar_google_connections DROP CONSTRAINT calendar_google_connections_pkey;

ALTER TABLE calendar_google_connections 
    ADD PRIMARY KEY (user_id, connection_id);

-- Same Google account (calendar_id) must not create a second row for the same user
ALTER TABLE calendar_google_connections 
    ADD CONSTRAINT calendar_google_connections_account_unique UNIQUE (user_id, calendar_id);

ALTER TABLE calendar_google_connections 
    ALTER COLUMN connection_id SET DEFAULT gen_random_uuid();

-- New connections start as read-only; existing rows keep can_schedule=true from V45
ALTER TABLE calendar_google_connections 
    ALTER COLUMN can_schedule SET DEFAULT false;
