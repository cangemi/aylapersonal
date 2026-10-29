ALTER TABLE chat_conversations ADD COLUMN deleted_at timestamptz;
CREATE INDEX chat_conversations_visible ON chat_conversations(user_id,created_at DESC) WHERE deleted_at IS NULL;
CREATE TABLE conversation_session_cleanup (
 conversation_id uuid PRIMARY KEY REFERENCES chat_conversations(id) ON DELETE CASCADE,
 user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 profile varchar(64) NOT NULL, session_id varchar(128) NOT NULL,
 next_attempt_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz
);
