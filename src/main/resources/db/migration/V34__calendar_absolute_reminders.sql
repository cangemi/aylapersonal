ALTER TABLE calendar_reminders ADD COLUMN reminder_type varchar(16) NOT NULL DEFAULT 'BEFORE_START'
 CHECK (reminder_type IN ('BEFORE_START','AT_TIME'));
ALTER TABLE calendar_reminders ADD COLUMN action_key varchar(200);
ALTER TABLE calendar_reminders DROP CONSTRAINT calendar_reminders_event_id_event_version_minutes_before_key;
CREATE UNIQUE INDEX calendar_reminders_before_unique ON calendar_reminders(event_id,event_version,minutes_before) WHERE reminder_type='BEFORE_START';
CREATE UNIQUE INDEX calendar_reminders_at_unique ON calendar_reminders(event_id,event_version,due_at) WHERE reminder_type='AT_TIME';
CREATE UNIQUE INDEX calendar_reminders_action_unique ON calendar_reminders(action_key) WHERE action_key IS NOT NULL;
