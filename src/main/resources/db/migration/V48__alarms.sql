CREATE TABLE alarms (
    id uuid PRIMARY KEY,
    user_id varchar NOT NULL,
    title varchar(200) NOT NULL,
    description varchar(4000) NOT NULL DEFAULT '',
    alarm_type varchar(16) NOT NULL CHECK (alarm_type IN ('ONCE','DAILY')),
    alarm_at timestamptz,
    time_of_day time,
    time_zone varchar(80) NOT NULL DEFAULT 'America/Sao_Paulo',
    confirm_needed boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_fired_at timestamptz,
    confirm_pending boolean NOT NULL DEFAULT false,
    next_snooze_at timestamptz
);

CREATE INDEX alarms_user_idx ON alarms(user_id);

CREATE TABLE alarm_notifications (
    id uuid PRIMARY KEY,
    alarm_id uuid NOT NULL REFERENCES alarms(id) ON DELETE CASCADE,
    user_id varchar NOT NULL,
    due_at timestamptz NOT NULL,
    notified_at timestamptz,
    whatsapp_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (whatsapp_status IN ('PENDING','SENDING','SENT','SKIPPED','UNCERTAIN','FRONT_ONLY')),
    whatsapp_attempt_at timestamptz,
    read_at timestamptz,
    confirmed_at timestamptz,
    snooze boolean NOT NULL DEFAULT false
);

CREATE INDEX alarm_notifications_due ON alarm_notifications(due_at) WHERE notified_at IS NOT NULL;
CREATE INDEX alarm_notifications_alarm ON alarm_notifications(alarm_id);
