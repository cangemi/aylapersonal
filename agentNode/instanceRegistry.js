const USER_ID = /^[A-Za-z0-9_-]{1,64}$/;
const { createHash } = require('node:crypto');

/** One WhatsApp client per Ayla user; the platform owner keeps the original instance id. */
class InstanceRegistry {
    constructor({ config, pool, createRepository, createStore, createManager }) {
        this.config = config; this.pool = pool;
        this.createRepository = createRepository; this.createStore = createStore; this.createManager = createManager;
        this.entries = new Map();
    }
    instanceIdFor(userId) { return userId === this.config.ownerId ? this.config.instanceId : `u-${userId}`; }
    async persistedInstanceIdFor(userId,role) {
        const fallback = role === 'user' ? this.instanceIdFor(userId) : `a-${createHash('sha256').update(userId).digest('hex').slice(0,48)}`;
        const result = await this.pool.query(`select i.id from whatsapp_instances i
            left join whatsapp_sessions s on s.instance_id=i.id
            where i.owner_user_id=$1 and i.connection_role=$2
            order by (s.instance_id is not null) desc,(i.id=$3) desc,i.desired_connected desc,s.updated_at desc nulls last,i.updated_at desc,i.id limit 1`,[userId,role,fallback]);
        return result.rows[0]?.id || fallback;
    }
    async get(userId, role = 'user') {
        const id = userId || this.config.ownerId;
        if (!USER_ID.test(id)) throw Object.assign(new Error('Usuário inválido.'), { status: 400 });
        if (!['user', 'ayla'].includes(role)) throw Object.assign(new Error('Conexão inválida.'), { status: 400 });
        const entryKey = role === 'user' ? id : `${id}:ayla`;
        if (!this.entries.has(entryKey)) {
            const instanceId = await this.persistedInstanceIdFor(id,role);
            // Recheck after the database lookup so concurrent requests share one client.
            if (this.entries.has(entryKey)) { const entry=this.entries.get(entryKey);await entry.ready;return entry; }
            const config = { ...this.config, ownerId: id, connectionRole: role,instanceId,
                defaultProfile: id === this.config.ownerId ? 'default' : null };
            const repository = this.createRepository(this.pool, config);
            const manager = this.createManager({ config, repository, store: this.createStore(this.pool, config) });
            if (role === 'user') manager.getAssistant = async () => (await this.get(id, 'ayla')).manager;
            const entry = { repository, manager, ready: manager.start() };
            this.entries.set(entryKey, entry);
            entry.ready.catch(() => this.entries.delete(entryKey));
        }
        const entry = this.entries.get(entryKey);
        await entry.ready;
        return entry;
    }
    async startPersisted() {
        await this.get(this.config.ownerId);
        const rows = await this.pool.query("select distinct i.owner_user_id from whatsapp_instances i join auth_accounts a on a.user_id=i.owner_user_id where (i.desired_connected or i.state in ('ready','authenticated','reconnecting') or i.connection_mode='dedicated') and a.enabled");
        for (const row of rows.rows) {
            try { await this.get(row.owner_user_id); }
            catch (error) { console.error('[whatsapp] instância não iniciada:', error.code || error.name); }
        }
    }
    async shutdown() {
        await Promise.allSettled([...this.entries.values()].map(entry => entry.ready.then(() => entry.manager.shutdown())));
    }
}
module.exports = { InstanceRegistry };
