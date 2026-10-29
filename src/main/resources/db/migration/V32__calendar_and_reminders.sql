CREATE TABLE calendar_events (
 id uuid PRIMARY KEY, user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 title varchar(200) NOT NULL, description varchar(4000) NOT NULL DEFAULT '', location varchar(500) NOT NULL DEFAULT '',
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, time_zone varchar(80) NOT NULL DEFAULT 'America/Sao_Paulo',
 all_day boolean NOT NULL DEFAULT false, status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','CANCELLED')),
 reminder_minutes integer[] NOT NULL DEFAULT '{}', version bigint NOT NULL DEFAULT 0,
 google_id varchar(1100), google_etag varchar(300), google_dirty boolean NOT NULL DEFAULT true,
 source varchar(10) NOT NULL DEFAULT 'AYLA' CHECK(source IN ('AYLA','GOOGLE')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ends_at>starts_at), UNIQUE(user_id,google_id)
);
CREATE INDEX calendar_events_period ON calendar_events(user_id,starts_at) WHERE status='ACTIVE';
CREATE TABLE calendar_reminders (
 id uuid PRIMARY KEY, event_id uuid NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
 event_version bigint NOT NULL, minutes_before integer NOT NULL CHECK(minutes_before BETWEEN 0 AND 40320),
 due_at timestamptz NOT NULL, notified_at timestamptz, read_at timestamptz, cancelled boolean NOT NULL DEFAULT false,
 whatsapp_status varchar(15) NOT NULL DEFAULT 'PENDING' CHECK(whatsapp_status IN ('PENDING','SENDING','SENT','UNCERTAIN','SKIPPED')),
 whatsapp_attempt_at timestamptz, UNIQUE(event_id,event_version,minutes_before)
);
CREATE INDEX calendar_reminders_due ON calendar_reminders(due_at) WHERE NOT cancelled;
CREATE TABLE calendar_google_connections (
 user_id varchar(255) PRIMARY KEY REFERENCES chat_users(user_id) ON DELETE CASCADE,
 refresh_secret text NOT NULL, calendar_id varchar(1100) NOT NULL, label varchar(300) NOT NULL, connected_at timestamptz NOT NULL DEFAULT now(), last_sync_at timestamptz,
 next_sync_at timestamptz NOT NULL DEFAULT now(), sync_status varchar(40) NOT NULL DEFAULT 'PENDING',
 connection_id uuid NOT NULL
);
CREATE TABLE calendar_oauth_states (
 state_hash varchar(64) PRIMARY KEY, user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL DEFAULT now()+interval '10 minutes'
);
CREATE TABLE calendar_action_receipts (
 user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 action_key varchar(200) NOT NULL, fingerprint varchar(64) NOT NULL, event_id uuid NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
 PRIMARY KEY(user_id,action_key)
);
