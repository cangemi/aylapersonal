CREATE TABLE finance_chat_drafts (
    conversation_id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL,
    draft_id uuid NOT NULL,
    type varchar(10) NOT NULL CHECK(type IN ('INCOME','EXPENSE')),
    amount_text varchar(80),
    unit varchar(8) CHECK(unit IN ('BRL','CENT')),
    description varchar(300),
    occurred_date date NOT NULL,
    occurred_at timestamptz,
    FOREIGN KEY(user_id,conversation_id) REFERENCES chat_conversations(user_id,id)
);
CREATE TABLE finance_chat_messages (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL,
    user_id varchar(255) NOT NULL,
    user_message text NOT NULL,
    response text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id,conversation_id) REFERENCES chat_conversations(user_id,id)
);
CREATE INDEX finance_chat_messages_conversation ON finance_chat_messages(user_id,conversation_id,created_at DESC);
