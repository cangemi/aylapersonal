ALTER TABLE application_events ADD COLUMN request_key varchar(128);
ALTER TABLE ai_usage_events ADD COLUMN request_id varchar(64);
CREATE INDEX application_events_request_key ON application_events(request_key) WHERE request_key IS NOT NULL;
CREATE INDEX ai_usage_request ON ai_usage_events(request_id) WHERE request_id IS NOT NULL;
