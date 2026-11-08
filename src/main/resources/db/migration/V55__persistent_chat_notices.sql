CREATE UNIQUE INDEX proactive_chat_job_notice ON proactive_messages(source_id) WHERE source='chat-job-notice';

INSERT INTO proactive_messages(id,user_id,conversation_id,role,text,source,source_id,created_at)
SELECT id,user_id,conversation_id,'assistant',notice,'chat-job-notice',id,coalesce(finished_at,created_at)
FROM chat_jobs WHERE status IN ('FAILED','NEEDS_REVIEW','CANCELLED') AND notice IS NOT NULL
ON CONFLICT DO NOTHING;
