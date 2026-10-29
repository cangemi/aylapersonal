CREATE TABLE chat_jobs (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    user_id varchar(255) NOT NULL REFERENCES auth_accounts(user_id) ON DELETE CASCADE,
    conversation_id uuid NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    request_key varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL,
    source varchar(16) NOT NULL CHECK(source IN ('WEB','WHATSAPP')),
    whatsapp_chat_id uuid,
    status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED','NEEDS_REVIEW','CANCELLED')),
    request_json text,
    result_json text,
    user_message text NOT NULL,
    notice varchar(400),
    created_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    dispatched_at timestamptz,
    lease_until timestamptz,
    lease_token uuid,
    attempts integer NOT NULL DEFAULT 0,
    delivered_at timestamptz,
    review_released_at timestamptz,
    UNIQUE(user_id,request_key)
);
CREATE INDEX chat_jobs_pending ON chat_jobs(available_at,sequence) WHERE status='QUEUED';
CREATE INDEX chat_jobs_conversation_order ON chat_jobs(conversation_id,sequence) WHERE status IN ('QUEUED','RUNNING','NEEDS_REVIEW');
CREATE INDEX chat_jobs_delivery ON chat_jobs(user_id,source,sequence) WHERE delivered_at IS NULL;
CREATE INDEX chat_jobs_running ON chat_jobs(lease_until) WHERE status='RUNNING';
