ALTER TABLE ai_configurations
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 100;

ALTER TABLE ai_configurations
    ADD CONSTRAINT ai_configurations_priority_nonnegative CHECK (priority >= 0);

CREATE INDEX idx_ai_configurations_routing ON ai_configurations (enabled, priority, id);
