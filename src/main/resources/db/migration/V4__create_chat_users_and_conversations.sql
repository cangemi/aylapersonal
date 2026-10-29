CREATE TABLE chat_users (
    user_id VARCHAR(255) PRIMARY KEY,
    hermes_profile VARCHAR(64),
    current_conversation_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE chat_conversations (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES chat_users(user_id),
    hermes_session_id VARCHAR(128) NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (user_id, id)
);
ALTER TABLE chat_users ADD CONSTRAINT fk_current_conversation_owner
    FOREIGN KEY (user_id, current_conversation_id) REFERENCES chat_conversations(user_id, id);
CREATE INDEX idx_chat_conversations_user ON chat_conversations(user_id, created_at DESC);
