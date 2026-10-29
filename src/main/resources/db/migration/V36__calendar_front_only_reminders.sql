ALTER TABLE calendar_reminders DROP CONSTRAINT calendar_reminders_whatsapp_status_check;
ALTER TABLE calendar_reminders ADD CONSTRAINT calendar_reminders_whatsapp_status_check
 CHECK(whatsapp_status IN ('PENDING','SENDING','SENT','UNCERTAIN','SKIPPED','FRONT_ONLY'));
ALTER TABLE whatsapp_chats ADD COLUMN history_reset_at timestamptz;
