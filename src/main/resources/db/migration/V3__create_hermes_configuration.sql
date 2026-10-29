CREATE TABLE hermes_configuration (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    model VARCHAR(255),
    instructions VARCHAR(4000) NOT NULL DEFAULT ''
);
INSERT INTO hermes_configuration (id, model, instructions) VALUES (1, NULL, '');
