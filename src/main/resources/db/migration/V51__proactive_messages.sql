CREATE TABLE proactive_messages (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL DEFAULT 'assistant',
    text VARCHAR(4000) NOT NULL,
    source VARCHAR(40) NOT NULL,
    source_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX proactive_messages_conversation ON proactive_messages(conversation_id, created_at);
CREATE INDEX proactive_messages_user ON proactive_messages(user_id, created_at DESC);