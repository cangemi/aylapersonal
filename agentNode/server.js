const express = require('express');
const crypto = require('node:crypto');
const { loadConfig } = require('./config');
const { createPool } = require('./db');
const { WhatsappRepository } = require('./whatsappRepository');
const { PostgresSessionStore } = require('./postgresSessionStore');
const { WhatsappManager } = require('./whatsappManager');
const { InstanceRegistry } = require('./instanceRegistry');

function createApp({ manager, repository, registry, apiKey }) {
    const app = express();
    const lookup = registry || { get: async () => ({ manager, repository }) };
    app.disable('x-powered-by'); app.use(express.json({ limit: '32kb' }));
    app.use((req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });
    app.get('/health', async (req, res) => {
        try { const { repository: repo } = await lookup.get(); await repo.instance(); res.json({ status: 'UP' }); }
        catch { res.status(503).json({ status: 'DOWN' }); }
    });
    app.use((req, res, next) => {
        const supplied = Buffer.from(req.get('Authorization')?.replace(/^Bearer /, '') || '');
        const expected = Buffer.from(apiKey);
        if (!req.get('Authorization')?.startsWith('Bearer ') || supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected))
            return res.status(401).json({ error: 'Não autorizado.' });
        next();
    });
    const scoped = (handler) => async (req, res, next) => {
        try {
            const entry = await lookup.get(req.path === '/internal/password-recovery' ? req.body?.userId : req.get('X-Ayla-User'));
            await handler(entry, req, res, next);
        } catch (error) { next(error); }
    };
    app.get('/status', scoped(async ({ manager }, req, res) => res.json(await manager.status())));
    app.get('/qr', scoped(async ({ manager }, req, res) => res.json(manager.getQR())));
    app.put('/configuration', scoped(async ({ manager }, req, res) => res.json(await manager.configure(req.body))));
    const assistant = async (manager) => {
        if (manager.connectionMode !== 'dedicated') throw Object.assign(new Error('Selecione o número exclusivo da Ayla.'), {status:409});
        return manager.getAssistant();
    };
    app.get('/ayla/qr', scoped(async ({manager}, req, res) => res.json((await assistant(manager)).getQR())));
    for (const action of ['connect', 'disconnect', 'restart', 'logout']) {
        app.post(`/ayla/${action}`, scoped(async ({manager}, req, res) => res.json(await (await assistant(manager))[action]())));
    }
    app.post('/connect', scoped(async ({ manager }, req, res) => res.status(202).json(await manager.connect())));
    app.post('/disconnect', scoped(async ({ manager }, req, res) => res.json(await manager.disconnect())));
    app.post('/restart', scoped(async ({ manager }, req, res) => res.status(202).json(await manager.restart())));
    app.post('/logout', scoped(async ({ manager }, req, res) => res.json(await manager.logout())));
    app.post('/personal-chat', scoped(async ({ manager }, req, res) => res.json(await manager.createPersonalChat())));
    app.post('/internal/conversations/:id/:action', (req,res,next)=>{
        if(!/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(req.params.id)||!['removal-check','remove-personal-group'].includes(req.params.action))
            return next(Object.assign(new Error('Operação inválida'),{status:400}));
        next();
    },scoped(async({manager},req,res)=>res.json(req.params.action==='removal-check'
        ?await manager.conversationRemovalPlan(req.params.id):await manager.removePersonalConversation(req.params.id))));
    app.post('/internal/calendar-reminder', (req,res,next) => {
        if(!req.body || Object.keys(req.body).some(key=>key!=='id') || !/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(req.body.id||''))
            return next(Object.assign(new Error('Lembrete inválido'),{status:400}));
        next();
    }, scoped(async ({manager},req,res)=>res.json(await manager.sendCalendarReminder(req.body.id))));
    app.post('/internal/password-recovery', (req, res, next) => {
        const body=req.body;
        if(!body || Array.isArray(body) || Object.keys(body).some(k=>!['userId','registeredNumber','password','requestId'].includes(k))
            || typeof body.userId!=='string' || body.userId.length>255 || !/^[1-9]\d{7,14}$/.test(body.registeredNumber||'') || !/^[A-Za-z0-9_-]{24}$/.test(body.password||'')
            || !/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(body.requestId||''))
            return next(Object.assign(new Error('Dados de recuperação inválidos'),{status:400}));
        next();
    }, scoped(async ({ manager }, req, res) => res.json(await manager.sendPasswordRecovery(req.body))));
    app.get('/chats', scoped(async ({ repository: repo }, req, res) => res.json(await repo.list())));
    const input = (req, res, next) => {
        if (!req.body || Array.isArray(req.body) || Object.keys(req.body).some(k => !['chatId', 'phone', 'name', 'enabled','mentionOnly'].includes(k)))
            throw Object.assign(new Error('Dados de conversa inválidos.'), { status: 400 });
        if (req.params.id && !/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(req.params.id))
            throw Object.assign(new Error('Identificador inválido.'), { status: 400 });
        next();
    };
    app.post('/chats', input, scoped(async ({ repository: repo }, req, res) => res.status(201).json(await repo.authorize(req.body, 'api'))));
    app.put('/chats/:id', input, scoped(async ({ repository: repo }, req, res) => res.json(await repo.update(req.params.id, req.body))));
    app.delete('/chats/:id', scoped(async ({ repository: repo }, req, res) => { await repo.remove(req.params.id); res.json({ deleted: true }); }));

    app.use((error, req, res, next) => {
        const status = error.code === '23505' ? 409 : error.code === '22P02' ? 400 : error.status || 503;
        console.warn('[whatsapp] HTTP rejeitado:', req.method, req.route?.path || 'rota não identificada', status, error.code || error.name);
        if (status >= 500) console.error('[whatsapp] requisição falhou:', error.code || error.name);
        res.status(status).json({ error: status === 409 ? 'Número ou conversa já cadastrado.' : status < 500 ? 'Dados inválidos ou conversa não encontrada.' : 'O serviço WhatsApp não conseguiu concluir a operação.' });
    });
    return app;
}
async function main() {
    const config = loadConfig();
    const pool = createPool();
    const registry = new InstanceRegistry({ config, pool,
        createRepository: (p, c) => new WhatsappRepository(p, c),
        createStore: (p, c) => new PostgresSessionStore(p, c),
        createManager: options => new WhatsappManager(options) });
    await registry.startPersisted();
    const app = createApp({ registry, apiKey: config.apiKey });
    const server = app.listen(config.port, config.host, () => console.log(`[whatsapp] agentNode em ${config.host}:${config.port}; use POST /api/v1/whatsapp/connect na API Java.`));
    let stopping = false;
    const shutdown = async () => {
        if (stopping) return; stopping = true;
        const deadline = setTimeout(() => process.exit(1), 20000); deadline.unref();
        server.close();
        try { await registry.shutdown(); await pool.end(); process.exit(0); }
        catch { process.exit(1); }
    };
    process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown);
}
if (require.main === module) main().catch(error => { console.error('[whatsapp] Falha ao iniciar; aplique as migrations Java e confira as variáveis e o PostgreSQL:', error.code || error.name); process.exitCode = 1; });
module.exports = { createApp, main };
