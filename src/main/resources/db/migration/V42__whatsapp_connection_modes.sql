ALTER TABLE whatsapp_instances
    ADD COLUMN connection_mode varchar(16) NOT NULL DEFAULT 'own'
        CHECK (connection_mode IN ('own', 'dedicated')),
    ADD COLUMN connection_role varchar(16) NOT NULL DEFAULT 'user'
        CHECK (connection_role IN ('user', 'ayla'));
