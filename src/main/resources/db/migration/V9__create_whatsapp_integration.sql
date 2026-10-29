CREATE TABLE whatsapp_instances (
    id varchar(64) PRIMARY KEY,
    owner_user_id varchar(255) NOT NULL REFERENCES chat_users(user_id),
    desired_connected boolean NOT NULL DEFAULT false,
    state varchar(32) NOT NULL DEFAULT 'disconnected',
    connected_number varchar(40),
    connected_name varchar(160),
    last_error varchar(300),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE whatsapp_sessions (
    instance_id varchar(64) PRIMARY KEY REFERENCES whatsapp_instances(id),
    session_name varchar(128) NOT NULL,
    encrypted_data bytea NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE whatsapp_chats (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id varchar(64) NOT NULL REFERENCES whatsapp_instances(id),
    chat_id varchar(128) NOT NULL,
    phone varchar(32),
    display_name varchar(160) NOT NULL,
    is_group boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    conversation_id uuid REFERENCES chat_conversations(id),
    authorized_by varchar(128),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(instance_id,chat_id)
);
CREATE UNIQUE INDEX whatsapp_chat_phone ON whatsapp_chats(instance_id,phone) WHERE phone IS NOT NULL;
CREATE TABLE whatsapp_messages (
    instance_id varchar(64) NOT NULL REFERENCES whatsapp_instances(id),
    message_id varchar(255) NOT NULL,
    chat_id varchar(128) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'processing',
    response_text text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(instance_id,message_id)
);
