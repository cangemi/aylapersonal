CREATE TABLE shopping_whatsapp_polls (
 id uuid PRIMARY KEY, user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 contact_id uuid NOT NULL REFERENCES whatsapp_chats(id) ON DELETE CASCADE,
 active boolean NOT NULL DEFAULT true, revision bigint NOT NULL DEFAULT 1, sent_revision bigint NOT NULL DEFAULT 0,
 request_key varchar(200), history_reset_at timestamptz, signature text, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX shopping_poll_active_contact ON shopping_whatsapp_polls(user_id,contact_id) WHERE active;
CREATE TABLE shopping_poll_messages (
 message_id varchar(250) PRIMARY KEY, poll_id uuid NOT NULL REFERENCES shopping_whatsapp_polls(id) ON DELETE CASCADE,
 options jsonb NOT NULL, selected jsonb NOT NULL DEFAULT '[]', active boolean NOT NULL DEFAULT false,
 last_vote_at bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shopping_poll_message_active ON shopping_poll_messages(poll_id) WHERE active;
CREATE TABLE shopping_poll_events (
 id uuid PRIMARY KEY, poll_id uuid NOT NULL REFERENCES shopping_whatsapp_polls(id) ON DELETE CASCADE,
 event_key varchar(400) NOT NULL UNIQUE, body text NOT NULL, batch_key varchar(100),
 job_id uuid REFERENCES chat_jobs(id) ON DELETE SET NULL,
 created_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz
);
CREATE INDEX shopping_poll_event_pending ON shopping_poll_events(created_at) WHERE processed_at IS NULL;
