ALTER TABLE auth_accounts ADD COLUMN recovery_phone varchar(40);
UPDATE auth_accounts a SET recovery_phone=(
    SELECT i.connected_number FROM whatsapp_instances i
    WHERE i.owner_user_id=a.user_id AND i.connected_number IS NOT NULL
    ORDER BY (i.state='ready') DESC,i.updated_at DESC LIMIT 1
);
