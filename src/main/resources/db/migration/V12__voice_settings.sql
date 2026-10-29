CREATE TABLE voice_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    voice VARCHAR(80) NOT NULL DEFAULT 'pt-BR-FranciscaNeural',
    speed NUMERIC(4,2) NOT NULL DEFAULT 1.02 CHECK (speed BETWEEN 0.75 AND 1.50),
    pitch_hz INTEGER NOT NULL DEFAULT 2 CHECK (pitch_hz BETWEEN -50 AND 50)
);
INSERT INTO voice_settings (id) VALUES (1);
