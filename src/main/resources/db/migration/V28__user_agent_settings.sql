CREATE TABLE user_agent_settings (
    user_id varchar(255) PRIMARY KEY REFERENCES chat_users(user_id) ON DELETE CASCADE,
    model varchar(255),
    instructions varchar(4000) NOT NULL DEFAULT '',
    voice varchar(64),
    speed numeric(3,2),
    pitch_hz integer
);
