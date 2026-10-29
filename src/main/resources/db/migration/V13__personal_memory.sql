CREATE TABLE personal_memories (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    subject varchar(160) NOT NULL,
    attribute varchar(80) NOT NULL,
    kind varchar(20) NOT NULL CHECK (kind IN ('PROFILE','PREFERENCE','PET','POSSESSION','EXPERIENCE')),
    content varchar(800) NOT NULL,
    source varchar(10) NOT NULL CHECK (source IN ('USER','IMAGE')),
    evidence varchar(800) NOT NULL,
    importance integer NOT NULL CHECK (importance BETWEEN 1 AND 5),
    conversation_id uuid NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    search_document tsvector GENERATED ALWAYS AS
        (setweight(to_tsvector('portuguese',subject),'A') || setweight(to_tsvector('portuguese',content),'B')) STORED,
    UNIQUE(user_id,subject,attribute),
    FOREIGN KEY(user_id,conversation_id) REFERENCES chat_conversations(user_id,id)
);
CREATE INDEX personal_memories_search ON personal_memories USING gin(search_document);
CREATE INDEX personal_memories_owner ON personal_memories(user_id,updated_at DESC);
