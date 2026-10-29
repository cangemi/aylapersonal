CREATE TABLE finance_agent_turns (
    sequence bigserial UNIQUE NOT NULL,
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL,
    conversation_id uuid NOT NULL,
    user_message text NOT NULL,
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id,conversation_id) REFERENCES chat_conversations(user_id,id)
);
CREATE INDEX finance_agent_turns_context ON finance_agent_turns(user_id,conversation_id,sequence DESC);
