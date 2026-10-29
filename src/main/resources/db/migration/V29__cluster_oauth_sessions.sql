-- OAuth polling may reach another API replica. Keep ownership/expiration in the shared database.
create table hermes_oauth_sessions (
    user_id varchar(255) primary key references chat_users(user_id) on delete cascade,
    session_id varchar(128) not null,
    profile varchar(64) not null,
    expires_at timestamptz not null
);
