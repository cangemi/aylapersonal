const USER_ID = /^[A-Za-z0-9_-]{1,64}$/;

/** One WhatsApp client per Ayla user; the platform owner keeps the original instance id. */
class InstanceRegistry {
    constructor({ config, pool, createRepository, createStore, createManager }) {
        this.config = config; this.pool = pool;
        this.createRepository = createRepository; this.createStore = createStore; this.createManager = createManager;
        this.entries = new Map();
    }
    instanceIdFor(userId) { return userId === this.config.ownerId ? this.config.instanceId : `u-${userId}`; }
    async get(userId) {
        const id = userId || this.config.ownerId;
        if (!USER_ID.test(id)) throw Object.assign(new Error('Usuário inválido.'), { status: 400 });
        if (!this.entries.has(id)) {
            const config = { ...this.config, ownerId: id, instanceId: this.instanceIdFor(id),
                defaultProfile: id === this.config.ownerId ? 'default' : null };
            const repository = this.createRepository(this.pool, config);
            const manager = this.createManager({ config, repository, store: this.createStore(this.pool, config) });
            const entry = { repository, manager, ready: manager.start() };
            this.entries.set(id, entry);
            entry.ready.catch(() => this.entries.delete(id));
        }
        const entry = this.entries.get(id);
        await entry.ready;
        return entry;
    }
    async startPersisted() {
        await this.get(this.config.ownerId);
        const rows = await this.pool.query('select i.owner_user_id from whatsapp_instances i join auth_accounts a on a.user_id=i.owner_user_id where i.desired_connected and a.enabled');
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
