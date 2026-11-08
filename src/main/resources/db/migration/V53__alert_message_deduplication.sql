CREATE UNIQUE INDEX proactive_alert_source ON proactive_messages(source, source_id)
    WHERE source IN ('alarm-notification', 'calendar-reminder');
