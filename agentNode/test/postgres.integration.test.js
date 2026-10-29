const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Pool } = require('pg');
const { PostgresSessionStore } = require('../postgresSessionStore');
const { WhatsappRepository } = require('../whatsappRepository');

test('PostgreSQL persists contact CRUD, message deduplication and encrypted RemoteAuth backup/restoration',
    { skip: !process.env.WHATSAPP_TEST_DATABASE_URL }, async t => {
        const pool = new Pool({ connectionString: process.env.WHATSAPP_TEST_DATABASE_URL });
        const root = await fs.mkdtemp(path.join(os.tmpdir(), 'ayla-whatsapp-test-'));
        t.after(async () => {
            await pool.end();
            if (path.dirname(root) === os.tmpdir() && path.basename(root).startsWith('ayla-whatsapp-test-')) await fs.rm(root, { recursive: true, force: true });
        });
        const config = { instanceId: 'test-' + crypto.randomUUID(), ownerId: 'wa-owner-' + crypto.randomUUID(), authDir: root, sessionKey: crypto.randomBytes(32) };
        const repo = new WhatsappRepository(pool, config);
        await repo.init(); await repo.state('ready', true, { number: '5511888888888' });
        assert.equal((await repo.instance()).desired_connected, true);
        const contact = await repo.authorize({ phone: '5511999999999', name: 'Contato' });
        assert.equal(contact.chatId, '5511999999999@c.us');
        assert.equal((await repo.list()).length, 1);
        assert.equal((await repo.find('999999999999999999@lid', '5511999999999')).id, contact.id);
        await repo.update(contact.id, { enabled: false }); assert.equal((await repo.get(contact.id)).enabled, false);
        await repo.authorize({ phone: '5511999999999', name: 'Reativado' });
        assert.equal((await repo.get(contact.id)).enabled, true);
        await repo.update(contact.id,{mentionOnly:true});
        assert.equal((await new WhatsappRepository(pool,config).get(contact.id)).mentionOnly,true);
        await repo.authorize({phone:'5511999999999',name:'Automático',mentionOnly:false});
        assert.equal((await repo.get(contact.id)).mentionOnly,false);
        // Configuring another primary demotes the previous one and detaches its old session.
        await pool.query('insert into chat_conversations(id,user_id,hermes_session_id,title,created_at) values ($1,$2,$3,$4,CURRENT_TIMESTAMP)',
            [crypto.randomUUID(),config.ownerId,'old-primary-session','Principal antiga']);
        await pool.query("update whatsapp_chats set conversation_id=(select id from chat_conversations where hermes_session_id='old-primary-session') where id=$1",[contact.id]);
        const other=await repo.authorize({phone:'5511777777777',name:'Principal nova',mentionOnly:false});
        assert.equal((await repo.get(contact.id)).mentionOnly,true);
        assert.equal((await repo.get(contact.id)).conversationId,null);
        assert.equal(other.mentionOnly,false);
        await repo.remove(other.id);
        assert.equal(await repo.claim('message-1', contact.chatId), true);
        assert.equal(await repo.claim('message-1', contact.chatId), false);
        await repo.finish('message-1', 'sent', '-------Ayla--------\nResposta');
        await repo.markSensitive('recovery-message',contact.chatId);
        await repo.finish('recovery-message','sent','this-secret-must-not-be-stored');
        assert.deepEqual(await repo.sensitiveMessageIds(contact.chatId),['recovery-message']);
        const privateRow=await pool.query('select response_text,sensitive from whatsapp_messages where instance_id=$1 and message_id=$2',[config.instanceId,'recovery-message']);
        assert.equal(privateRow.rows[0].response_text,null);assert.equal(privateRow.rows[0].sensitive,true);
        const store = new PostgresSessionStore(pool, config), session = store.sessionName;
        const archive = path.join(root, session + '.zip');
        const original = Buffer.from('session-zip-test-fixture'); await fs.writeFile(archive, original);
        await store.save({ session }); assert.equal(await store.sessionExists({ session }), true);
        const saved = await pool.query('select encrypted_data from whatsapp_sessions where instance_id=$1', [config.instanceId]);
        assert.ok(!saved.rows[0].encrypted_data.equals(original));
        await fs.unlink(archive);
        await new PostgresSessionStore(pool, config).extract({ session, path: archive });
        assert.deepEqual(await fs.readFile(archive), original);
        await assert.rejects(() => new PostgresSessionStore(pool, { ...config, sessionKey: crypto.randomBytes(32) }).extract({ session, path: archive }));
        await assert.rejects(() => store.extract({ session, path: path.join(root, '..', 'invalid.zip') }));
        await store.delete({ session }); assert.equal(await store.sessionExists({ session }), false);
        await repo.remove(contact.id); assert.equal((await repo.list()).length, 0);
    });
