-- Add can_schedule column to calendar_google_connections
ALTER TABLE calendar_google_connections
    ADD COLUMN can_schedule boolean NOT NULL DEFAULT true;

-- Change primary key to allow multiple Google accounts per user
-- First, drop existing primary key (if needed for older versions)
-- Then add composite primary key on (user_id, connection_id)

-- Note: For existing data, can_schedule defaults to true (Ayla can schedule)