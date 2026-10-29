CREATE TABLE calendar_conversation_plans (
 conversation_id uuid PRIMARY KEY REFERENCES chat_conversations(id) ON DELETE CASCADE,
 user_id varchar(255) NOT NULL REFERENCES chat_users(user_id) ON DELETE CASCADE,
 plan jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (jsonb_typeof(plan)='object')
);
CREATE INDEX calendar_plans_user ON calendar_conversation_plans(user_id);
