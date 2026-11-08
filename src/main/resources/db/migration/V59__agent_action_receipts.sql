-- Successful internal tool mutations, recorded before their HTTP response is delivered.
-- No arguments, results, prompts or credentials are retained here.
CREATE TABLE agent_action_receipts (
    id uuid PRIMARY KEY,
    turn_id uuid NOT NULL REFERENCES finance_agent_turns(id) ON DELETE CASCADE,
    domain varchar(32) NOT NULL,
    operation varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_action_receipts_turn ON agent_action_receipts(turn_id,domain);
