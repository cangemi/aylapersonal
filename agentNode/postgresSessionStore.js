const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');

class PostgresSessionStore {
    constructor(pool, config) {
        this.pool = pool; this.config = config;
        this.sessionName = `RemoteAuth-${config.instanceId}`;
    }
    check(session) { if (session !== this.sessionName) throw new Error('Sessão WhatsApp inválida.'); }
    async sessionExists({ session }) {
        this.check(session);
        const result = await this.pool.query('select 1 from whatsapp_sessions where instance_id=$1 and session_name=$2', [this.config.instanceId, session]);
        return result.rowCount > 0;
    }
    async save({ session }) {
        this.check(session);
        const plain = await fs.readFile(path.join(this.config.authDir, `${session}.zip`));
        const iv = crypto.randomBytes(12);
        const cipher = crypto.createCipheriv('aes-256-gcm', this.config.sessionKey, iv);
        const payload = Buffer.concat([Buffer.from([1]), iv, cipher.update(plain), cipher.final(), cipher.getAuthTag()]);
        await this.pool.query(`insert into whatsapp_sessions(instance_id,session_name,encrypted_data) values ($1,$2,$3)
            on conflict(instance_id) do update set session_name=excluded.session_name,encrypted_data=excluded.encrypted_data,updated_at=CURRENT_TIMESTAMP`, [this.config.instanceId, session, payload]);
    }
    async extract({ session, path: destination }) {
        this.check(session);
        if (path.resolve(destination) !== path.join(this.config.authDir, `${session}.zip`)) throw new Error('Destino da sessão inválido.');
        const result = await this.pool.query('select encrypted_data from whatsapp_sessions where instance_id=$1 and session_name=$2', [this.config.instanceId, session]);
        if (!result.rowCount) throw new Error('Sessão não encontrada.');
        const data = result.rows[0].encrypted_data;
        if (data.length < 29 || data[0] !== 1) throw new Error('Formato da sessão inválido.');
        const decipher = crypto.createDecipheriv('aes-256-gcm', this.config.sessionKey, data.subarray(1, 13));
        decipher.setAuthTag(data.subarray(data.length - 16));
        const plain = Buffer.concat([decipher.update(data.subarray(13, -16)), decipher.final()]);
        await fs.mkdir(this.config.authDir, { recursive: true });
        await fs.writeFile(destination, plain, { mode: 0o600 });
    }
    async delete({ session }) {
        this.check(session);
        await this.pool.query('delete from whatsapp_sessions where instance_id=$1 and session_name=$2', [this.config.instanceId, session]);
    }
}
module.exports = { PostgresSessionStore };
