ALTER TABLE alarms ADD COLUMN day_of_week SMALLINT;
ALTER TABLE alarms DROP CONSTRAINT alarms_alarm_type_check;
ALTER TABLE alarms ADD CONSTRAINT alarms_alarm_type_check CHECK (alarm_type IN ('ONCE','DAILY','WEEKLY'));
ALTER TABLE alarms ADD CONSTRAINT alarms_weekly_day_check CHECK (
  (alarm_type <> 'WEEKLY') OR (day_of_week BETWEEN 1 AND 7)
);