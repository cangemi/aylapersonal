-- Keep the most recently configured automatic chat as the primary chat.
WITH ranked AS (
    SELECT id,row_number() OVER(PARTITION BY instance_id ORDER BY updated_at DESC,id) AS position
    FROM whatsapp_chats WHERE enabled AND NOT mention_only
)
UPDATE whatsapp_chats SET mention_only=true,conversation_id=null,updated_at=CURRENT_TIMESTAMP
WHERE id IN (SELECT id FROM ranked WHERE position>1);

CREATE UNIQUE INDEX whatsapp_one_primary ON whatsapp_chats(instance_id) WHERE enabled AND NOT mention_only;

-- Action sessions previously received global personal memory in their prompt.
-- Keep their old sessions intact in Hermes, but stop using that contaminated context.
UPDATE chat_conversations SET hermes_session_id=gen_random_uuid()::text
WHERE id IN (SELECT conversation_id FROM whatsapp_chats WHERE mention_only AND conversation_id IS NOT NULL);

CREATE TABLE conversation_context_turns (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    user_id varchar(255) NOT NULL,
    user_message text NOT NULL,
    assistant_message text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    search_text tsvector GENERATED ALWAYS AS
      (to_tsvector('portuguese',user_message || ' ' || assistant_message)) STORED
);
CREATE INDEX conversation_context_turns_owner ON conversation_context_turns(user_id,created_at DESC);
CREATE INDEX conversation_context_turns_search ON conversation_context_turns USING gin(search_text);
