const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter, once } = require('node:events');
const { WhatsappManager } = require('../whatsappManager');
const { createMessageProcessor, formatReply, HEADER } = require('../messageProcessor');
const { createApp } = require('../server');

function fixture({ batchDelayMs = 0, batchTimers } = {}) {
    const contacts = new Map(), seen = new Set(), calls = [], sent = [];
    const repository = {
        async claim(id) { if (seen.has(id)) return false; seen.add(id); return true; },
        async find(chatId) { return contacts.get(chatId); },
        async get(id) { return [...contacts.values()].find(c => c.id === id); },
        async authorize(input) { const row = { ...input, id: '00000000-0000-4000-8000-' + String(contacts.size + 1).padStart(12, '0'), enabled: true }; contacts.set(input.chatId, row); return row; },
        async update(id, patch) { const row = await this.get(id); Object.assign(row, patch); return row; },
        async finish() {}, async instance() { return { desired_connected: false }; }, async state() {},
    };
    const client = new EventEmitter();
    Object.assign(client, { info: { wid: { user: '5511888888888', _serialized: '5511888888888@c.us' } },
        async personalGroupStatus(){return 'success';},
        async findPersonalGroup(){return null;},
        async sendMessage(chatId, text, options) { sent.push({ chatId, text, options }); }, async initialize() {}, async destroy() {} });
    const manager = new WhatsappManager({ config: { commandToken: 'ayla0101', instanceId: 'test', batchDelayMs }, repository, batchTimers,
        store: {}, clientFactory: () => client, processor: async input => { calls.push(input); return 'Oi, tudo bem?'; },
        renderQR: async () => 'data:image/png;base64,dGVzdA==', logger: { log() {}, error() {} } });
    manager.client = client; manager.phase = 'ready';
    manager.readySince = Math.floor(Date.now() / 1000) - 1;
    function message(id, body, from = '5511999999999@c.us', fromMe = true) {
        return { id: { _serialized: id }, body, from:fromMe?client.info.wid._serialized:from, to: fromMe ? from : client.info.wid._serialized, fromMe, timestamp: manager.readySince + 1,
            async getChat() { return { name: 'Contato', async sendStateTyping() {}, async clearState() {} }; } };
    }
    return { manager, repository, contacts, calls, sent, client, message };
}

function clock() {
    let now = 0, sequence = 0;
    const jobs = new Map();
    return {
        setTimeout(fn, delay) { const id = ++sequence; jobs.set(id, { at: now + delay, fn }); return id; },
        clearTimeout(id) { jobs.delete(id); },
        async tick(ms) {
            now += ms;
            for (const [id, job] of [...jobs].sort((a, b) => a[1].at - b[1].at)) {
                if (job.at <= now && jobs.delete(id)) job.fn();
            }
            await new Promise(resolve => setImmediate(resolve));
        },
    };
}

test('new batches are persisted before the previous answer finishes while deliveries stay ordered', async () => {
    const timers=clock(),f=fixture({batchDelayMs:10000,batchTimers:timers});
    await f.manager.enqueue(f.message('queue-enable','configurar ayla0101'));
    let releaseFirst;
    f.manager.processor=async input=>{
        f.calls.push(input.text);input.onQueued();
        return f.calls.length===1?new Promise(resolve=>{releaseFirst=resolve;}):'Segunda resposta';
    };
    await f.manager.enqueue(f.message('queue-first','Primeiro pedido'));
    await timers.tick(10000);
    await f.manager.enqueue(f.message('queue-second','Segundo pedido'));
    await timers.tick(10000);
    assert.deepEqual(f.calls,['Primeiro pedido','Segundo pedido']);
    assert.equal(f.sent.length,1);
    releaseFirst('Primeira resposta');
    await Promise.all([...f.manager.responseQueues.values()]);
    assert.match(f.sent[1].text,/Primeira resposta/);assert.match(f.sent[2].text,/Segunda resposta/);
});

test('an inactive account cannot configure or invoke Ayla through its WhatsApp connection',async()=>{
    const f=fixture();
    f.repository.accountEnabled=async()=>false;
    await f.manager.enqueue(f.message('inactive-enable','configurar Ayla0101'));
    await f.manager.enqueue(f.message('inactive-invoke','Ayla0101 anote arroz'));
    assert.equal(f.contacts.size,0);assert.equal(f.calls.length,0);assert.equal(f.sent.length,0);
});

test('personal Ayla group invites nobody, persists before authorization and is reused across calls and restarts', async () => {
    const f=fixture(), saved={desired_connected:true}; let creations=0, fail=true;
    let pictures=0;
    f.client.setPersonalGroupPicture=async id=>{assert.equal(id,saved.personal_group_chat_id);pictures++;return true;};
    f.repository.instance=async()=>saved;
    f.repository.savePersonalGroup=async id=>{saved.personal_group_chat_id=id;};
    const authorize=f.repository.authorize;
    f.repository.authorize=async input=>{
        assert.equal(saved.personal_group_chat_id,input.chatId);
        if(fail){fail=false;throw new Error('temporary database failure');}
        return authorize(input);
    };
    f.client.createGroup=async(title,participants,options)=>{
        creations++;
        assert.equal(title,'Ayla');assert.deepEqual(participants,[]);assert.equal(options.autoSendInviteV4,false);
        return {gid:{_serialized:'120363000000000000@g.us'}};
    };
    await assert.rejects(f.manager.createPersonalChat(),/temporary database failure/);
    const [a,b]=await Promise.all([f.manager.createPersonalChat(),f.manager.createPersonalChat()]);
    assert.equal(creations,1);assert.equal(a.chat.mentionOnly,false);assert.equal(b.created,false);
    assert.equal(a.pictureUpdated,true);assert.equal(pictures,2);
    assert.equal(f.contacts.size,1);assert.equal(f.sent.length,0);
    f.manager.personalGroupId=null; // Simulates losing process-local state.
    await f.manager.createPersonalChat();assert.equal(creations,1);
    f.client.personalGroupStatus=async()=> 'unknown';
    await assert.rejects(f.manager.createPersonalChat(),{status:503});assert.equal(creations,1);
});

test('personal group requires a ready connection and never authorizes an invalid creation result', async()=>{
    const f=fixture();let called=0;
    f.repository.savePersonalGroup=async()=>{throw new Error('must not persist');};
    f.client.createGroup=async()=>{called++;return 'CreateGroupError';};
    f.manager.phase='disconnected';
    await assert.rejects(f.manager.createPersonalChat(),{status:409});assert.equal(called,0);
    f.manager.phase='ready';
    await assert.rejects(f.manager.createPersonalChat(),{status:503});assert.equal(f.contacts.size,0);
});

test('replaces only a confirmed terminated personal group and preserves it as disabled history',async()=>{
    const f=fixture(),old='120363000000000000@g.us',fresh='120363000000000001@g.us';
    await f.repository.authorize({chatId:old,name:'Ayla',mentionOnly:false});
    const saved={personal_group_chat_id:old};
    f.repository.instance=async()=>saved;
    f.repository.savePersonalGroup=async id=>{saved.personal_group_chat_id=id;};
    f.client.personalGroupStatus=async id=>id===old?'terminated_local':'success';
    let creations=0;
    f.client.createGroup=async(title,participants)=>{assert.deepEqual(participants,[]);creations++;return{gid:{_serialized:fresh}};};
    f.client.setPersonalGroupPicture=async()=>true;
    const result=await f.manager.createPersonalChat();
    assert.equal(result.created,true);assert.equal(result.pictureUpdated,true);
    assert.equal(f.contacts.get(old).enabled,false);assert.equal(saved.personal_group_chat_id,fresh);
    await f.manager.createPersonalChat();assert.equal(creations,1);
});

test('recovers the active self-only group after creation result loss instead of duplicating it',async()=>{
    const f=fixture(),id='120363000000000002@g.us',saved={};let creations=0;
    f.repository.instance=async()=>saved;
    f.repository.savePersonalGroup=async value=>{saved.personal_group_chat_id=value;};
    f.client.findPersonalGroup=async()=>creations?id:null;
    f.client.createGroup=async()=>{creations++;throw new Error('ProtocolError after server creation');};
    f.client.setPersonalGroupPicture=async()=>true;
    const result=await f.manager.createPersonalChat();
    assert.equal(result.pictureUpdated,true);assert.equal(saved.personal_group_chat_id,id);
    await f.manager.createPersonalChat();assert.equal(creations,1);
});

test('ten quiet seconds combine fragments once, typing covers inference, and new fragments form a subsequent turn', async () => {
    const timers = clock(), f = fixture({ batchDelayMs: 10000, batchTimers: timers });
    const group = '120363000000000000@g.us', states = [], reactions = [];
    const own = (id, body) => ({ ...f.message(id, body), fromMe: true, from: f.client.info.wid._serialized, to: group });
    f.client.setChatTyping = async (id, state) => states.push({ id, state });
    f.client.sendReaction = async (id, emoji) => reactions.push({ id, emoji });
    await f.manager.enqueue(own('enable', 'configurar ayla0101'));
    assert.equal(f.sent.length, 1); // Commands do not wait for the buffer.
    const sendMessage = f.client.sendMessage;
    f.client.sendMessage = async (...args) => {
        assert.equal(reactions.at(-1).emoji, '⏳'); // Remains visible until delivery.
        return sendMessage(...args);
    };
    let resolveFirst;
    f.manager.processor = async input => {
        assert.equal(reactions.at(-1).emoji, '⏳');
        f.calls.push(input);
        return f.calls.length === 1 ? new Promise(resolve => { resolveFirst = resolve; }) : 'Segunda resposta';
    };
    await f.manager.enqueue(own('fragment-1', 'Gastei'));
    await timers.tick(9000);
    await f.manager.enqueue(own('fragment-2', '15 reais'));
    await timers.tick(9000);
    await f.manager.enqueue(own('fragment-3', 'com café'));
    await timers.tick(9999);
    assert.equal(f.calls.length, 0); assert.equal(states.length, 0); assert.equal(reactions.length, 0);
    await timers.tick(1);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].text, 'Gastei\n15 reais\ncom café');
    assert.deepEqual(states, [{ id: group, state: 'typing' }]);
    assert.deepEqual(reactions, [{ id: 'fragment-3', emoji: '⏳' }]);
    await f.manager.enqueue(own('fragment-3', 'com café')); // Redelivery must not create a second turn.
    await f.manager.enqueue(own('next-turn', 'E ontem?'));
    await timers.tick(10000);
    assert.equal(f.calls.length, 1); // Already buffered, waiting for the previous response.
    resolveFirst('Primeira resposta');
    await Promise.all([...f.manager.responseQueues.values()]);
    assert.deepEqual(f.calls.map(c => c.text), ['Gastei\n15 reais\ncom café', 'E ontem?']);
    assert.deepEqual(states.map(s => s.state), ['typing', 'stop', 'typing', 'stop']);
    assert.deepEqual(reactions, [
        { id: 'fragment-3', emoji: '⏳' }, { id: 'fragment-3', emoji: '' },
        { id: 'next-turn', emoji: '⏳' }, { id: 'next-turn', emoji: '' },
    ]);
    assert.equal(f.sent.length, 3);
});

test('processing reaction clears on AI/send failures, never triggers the agent, and is optional', async () => {
    for (const failure of ['ai', 'send', 'reaction']) {
        const f = fixture(), reactions = [];
        await f.manager.enqueue(f.message('enable', 'configurar ayla0101'));
        f.client.sendReaction = async (id, emoji) => {
            reactions.push({ id, emoji });
            if (failure === 'reaction') throw new Error('Reaction unavailable');
            // WhatsApp may emit an own reaction event; it must never enter the buffer.
            await f.manager.enqueue({ ...f.message('reaction-event', emoji), type: 'reaction', fromMe: true });
        };
        f.manager.processor = async input => {
            f.calls.push(input);
            if (failure === 'ai') throw new Error('AI unavailable');
            return 'Resposta';
        };
        if (failure === 'send') f.client.sendMessage = async () => { throw new Error('Send unavailable'); };
        await f.manager.enqueue(f.message('request', 'Oi!'));
        assert.equal(f.calls.length, 1);
        assert.deepEqual(reactions, [{ id: 'request', emoji: '⏳' }, { id: 'request', emoji: '' }]);
        if (failure === 'reaction') assert.equal(f.sent.at(-1).text, `${HEADER}\n\nResposta`);
    }
    const f = fixture(), reactions = [];
    await f.manager.enqueue(f.message('enable', 'configurar ayla0101'));
    f.client.sendReaction = async (id, emoji) => reactions.push({ id, emoji });
    let finish;
    f.manager.processor = () => new Promise(resolve => { finish = resolve; });
    const turn = f.manager.enqueue(f.message('pending', 'Oi!'));
    await new Promise(resolve => setImmediate(resolve));
    await f.manager.disconnect();
    assert.deepEqual(reactions, [{ id: 'pending', emoji: '⏳' }, { id: 'pending', emoji: '' }]);
    assert.equal(f.manager.processingReactions.size, 0);
    finish('Resposta que chegou após desconectar');
    await turn;
    assert.equal(reactions.length, 2);
});

test('buffers are isolated by chat and cancelled on disable/disconnect; typing failure does not block the answer', async () => {
    const timers = clock(), f = fixture({ batchDelayMs: 10000, batchTimers: timers });
    const a = '5511999999999@c.us', b = '5511777777777@c.us';
    f.client.setChatTyping = async () => { throw new Error('Presence unavailable'); };
    await f.manager.enqueue(f.message('enable-a', 'configurar ayla0101', a));
    await f.manager.enqueue(f.message('enable-b', 'configurar ayla0101', b));
    await f.manager.enqueue(f.message('a1', 'Conversa A', a));
    await f.manager.enqueue(f.message('b1', 'Conversa B', b));
    await timers.tick(9000);
    await f.manager.enqueue(f.message('a2', 'continuação A', a));
    await timers.tick(1000);
    assert.deepEqual(f.calls.map(c => c.text), ['Conversa B']);
    await timers.tick(9000);
    assert.deepEqual(f.calls.map(c => c.text), ['Conversa B', 'Conversa A\ncontinuação A']);
    await f.manager.enqueue(f.message('cancel-a', 'Não enviar A', a));
    await f.manager.enqueue(f.message('disable-a', 'tirar ayla0101', a));
    await timers.tick(10000);
    assert.equal(f.calls.length, 2);
    await f.manager.enqueue(f.message('cancel-b', 'Não enviar B', b));
    await f.manager.disconnect();
    await timers.tick(10000);
    assert.equal(f.calls.length, 2);
    assert.equal(f.manager.buffers.pending.size, 0);
});

test('only authorized chats reach Ayla; exact commands persist access, replies have header, echoes/redeliveries are ignored', async () => {
    const f = fixture();
    await f.manager.enqueue(f.message('1', 'Olá'));
    await f.manager.enqueue(f.message('2', 'configurar senha-errada'));
    assert.equal(f.calls.length, 0); assert.equal(f.sent.length, 0);
    await f.manager.enqueue(f.message('3', 'configurar ayla0101'));
    assert.equal(f.contacts.size, 1); assert.equal(f.calls.length, 0);
    await f.manager.enqueue(f.message('4', 'Quanto tenho?'));
    await f.manager.enqueue(f.message('4', 'Quanto tenho?'));
    assert.equal(f.calls.length, 1);
    assert.ok(f.sent.every(s => s.text.startsWith(HEADER)));
    assert.ok(f.sent.every(s => s.options.sendSeen === false));
    assert.equal(f.sent[1].text, `${HEADER}\n\nOi, tudo bem?`);
    await f.manager.enqueue(f.message('echo', f.sent[1].text, '5511888888888@c.us', true));
    assert.equal(f.calls.length, 1);
    await f.manager.enqueue(f.message('5', 'tirar ayla0101'));
    await f.manager.enqueue(f.message('6', 'Responda'));
    assert.equal(f.calls.length, 1); assert.equal(f.contacts.values().next().value.enabled, false);
});

test('startup and reconnect ignore history, offline backlog and old commands, accepting only new messages after ready', async () => {
    const f = fixture();
    f.manager.client = null; f.manager.phase = 'disconnected';
    await f.manager.connect();
    const buffered = f.message('startup', 'configurar ayla0101');
    buffered.timestamp = Math.floor(Date.now() / 1000) + 10;
    f.client.emit('message_create', buffered);
    f.client.emit('ready');
    await new Promise(r => setImmediate(r));
    assert.equal(f.contacts.size, 0); assert.equal(f.sent.length, 0);

    const cutoff = f.manager.readySince;
    for (const timestamp of [cutoff - 86400, cutoff - 1, cutoff, undefined, NaN, 'invalid']) {
        await f.manager.enqueue({ ...f.message('history-' + timestamp, 'configurar ayla0101'), timestamp });
    }
    assert.equal(f.contacts.size, 0); assert.equal(f.sent.length, 0);
    await f.manager.enqueue(f.message('new-command', 'configurar ayla0101'));
    await f.manager.enqueue({ ...f.message('offline-expense', 'Gastei 15 reais em café'), timestamp: cutoff - 100 });
    await f.manager.enqueue({ ...f.message('old-disable', 'tirar ayla0101'), timestamp: cutoff - 100 });
    await f.manager.enqueue(f.message('live', 'Oi, Ayla'));
    assert.equal(f.calls.length, 1); assert.equal(f.contacts.values().next().value.enabled, true);

    // A message already queued for the previous connection must not leak into a new one.
    f.manager.queues.set('5511999999999@c.us', Promise.resolve());
    const queued = f.manager.enqueue(f.message('queued-before-reconnect', 'Não responder'));
    f.manager.readySince = cutoff + 2;
    await queued;
    await f.manager.enqueue({ ...f.message('delayed-sync', 'Não responder'), timestamp: cutoff + 1 });
    await f.manager.enqueue(f.message('live-after-reconnect', 'Mensagem nova'));
    assert.deepEqual(f.calls.map(c => c.text), ['Oi, Ayla', 'Mensagem nova']);
    f.client.emit('change_state', 'TIMEOUT');
    await f.manager.enqueue({ ...f.message('during-outage', 'Não responder'), timestamp: cutoff + 10 });
    assert.equal(f.manager.readySince, null);
    f.client.emit('change_state', 'CONNECTED');
    await f.manager.enqueue({ ...f.message('network-backlog', 'Não responder'), timestamp: f.manager.readySince - 1 });
    await f.manager.enqueue(f.message('live-after-network', 'Voltou'));
    assert.deepEqual(f.calls.map(c => c.text), ['Oi, Ayla', 'Mensagem nova', 'Voltou']);
});

test('same-chat messages are serialized and disabling a chat during inference suppresses delivery', async () => {
    const f = fixture();
    await f.repository.authorize({ chatId: '5511999999999@c.us', name: 'Contato' });
    const order = [];
    f.manager.processor = async ({ text }) => { order.push(text); await new Promise(r => setTimeout(r, 5)); return text; };
    await Promise.all([f.manager.enqueue(f.message('a', 'primeira')), f.manager.enqueue(f.message('b', 'segunda'))]);
    assert.deepEqual(order, ['primeira', 'segunda']);
    f.manager.processor = async () => { await f.repository.update('00000000-0000-4000-8000-000000000001', { enabled: false }); return 'Não enviar'; };
    await f.manager.enqueue(f.message('c', 'terceira'));
    assert.equal(f.sent.length, 2);
});

test('owner can enable a group and talk there or in self-chat; Ayla replies never create a loop', async () => {
    const f = fixture(), group = '120363000000000000@g.us';
    f.client.getChatLabel = async () => { throw Object.assign(new Error('No key or key range specified'), { name: 'DataError' }); };
    let metadataReads = 0;
    const outgoing = (id, body, to) => ({ ...f.message(id, body), from: f.client.info.wid._serialized, to, fromMe: true,
        async getChat() { metadataReads++; throw new Error('Group metadata unavailable'); } });
    await f.manager.enqueue(outgoing('group-enable', 'configurar ayla0101', group));
    assert.equal(f.contacts.get(group).enabled, true);
    assert.equal(f.sent[0].chatId, group);
    await f.manager.enqueue(outgoing('group-owner', 'Gastei 15 reais em café', group));
    await f.manager.enqueue({ ...f.message('group-member', 'Quanto gastei?', group,false), author: '5511777777777@lid' });
    assert.equal(f.calls.length, 1);
    assert.ok(f.sent.every(s => s.chatId === group));
    await f.manager.enqueue(outgoing('group-echo', f.sent[1].text, group));
    await f.manager.enqueue(outgoing('self-enable', 'configurar ayla0101', f.client.info.wid._serialized));
    await f.manager.enqueue(outgoing('self-message', 'Oi', f.client.info.wid._serialized));
    assert.equal(f.calls.length, 2);
    assert.equal(f.sent.at(-1).chatId, f.client.info.wid._serialized);
    await f.manager.enqueue(outgoing('other-unauthorized', 'Oi', '5511666666666@c.us'));
    assert.equal(f.calls.length, 2);
    assert.equal(metadataReads, 0);
});

test('live bridge captures phone-sent events even without isNewMsg, binds once and never replays history', async () => {
    const vm = require('node:vm');
    const { installLiveMessages } = require('../liveMessages');
    const f = fixture(), collection = new EventEmitter();
    const subscribe = collection.on.bind(collection), unsubscribe = collection.off.bind(collection);
    collection.on = (names, handler) => { for (const name of names.split(' ')) subscribe(name, handler); };
    collection.off = (names, handler) => { for (const name of names.split(' ')) unsubscribe(name, handler); };
    const window = { require: () => ({ Msg: collection }), WWebJS: { getMessageModel: m => m } };
    f.client.pupPage = {
        async evaluate(fn) { return vm.runInNewContext('(' + fn.toString() + ')()', { window }); },
        async exposeFunction(name, fn) { window[name] = fn; },
    };
    await installLiveMessages(f.client); await installLiveMessages(f.client);
    assert.equal(collection.listenerCount('add'), 1);
    let pending = [];
    f.client.on('message_create', message => pending.push(f.manager.enqueue(message)));
    const group = '120363000000000000@g.us';
    const raw = (id, body, timestamp) => ({ id: { id, fromMe: true, remote: { user: group.split('@')[0], server: 'g.us' } }, body, t: timestamp,
        from: f.client.info.wid._serialized, to: group, type: 'chat', isNewMsg: false });
    // Avoid any real browser/API call when the command retrieves its chat.
    f.client.getChatById = async () => ({ name: 'Grupo' });
    assert.equal(pending.length, 0);
    collection.emit('add', raw('old', 'configurar ayla0101', f.manager.readySince - 100));
    await Promise.all(pending); pending = [];
    assert.equal(f.contacts.size, 0);
    const command = raw('new', 'configurar ayla0101', f.manager.readySince + 1);
    collection.emit('add', command); collection.emit('change', command);
    await Promise.all(pending); pending = [];
    assert.equal(f.contacts.get(group).enabled, true);
    assert.equal(f.sent.length, 1);
    // The standard library event has no _serialized either: both paths must
    // derive the same key, so the command is acknowledged only once.
    const { Message } = require('whatsapp-web.js');
    await f.manager.enqueue(new Message(f.client, command));
    assert.equal(f.sent.length, 1);
});

test('QR is available for frontend and console; disconnect keeps backup and logout deletes it', async () => {
    const f = fixture(); f.manager.client = null; f.manager.phase = 'disconnected';
    let deleted = 0, destroyed = 0;
    f.manager.store = { sessionName: 'RemoteAuth-test', async sessionExists() { return true; }, async delete() { deleted++; } };
    f.client.authStrategy = { async storeRemoteSession() {} };
    f.client.destroy = async () => { destroyed++; };
    await f.manager.connect();
    f.client.emit('qr', 'qr-temporario');
    await new Promise(r => setImmediate(r));
    assert.equal(f.manager.getQR().qr, 'qr-temporario');
    assert.match(f.manager.getQR().qrImageDataUrl, /^data:image\/png/);
    assert.ok(f.manager.getQR().qrAscii);
    await f.manager.disconnect(); assert.equal(deleted, 0); assert.equal(f.manager.getQR().qr, null);
    await f.manager.connect(); await f.manager.logout();
    assert.equal(deleted, 1); assert.equal(destroyed, 2);
});

test('message processor calls Java chat with trusted contact identity; header is added exactly once', async () => {
    let request;
    const process = createMessageProcessor({ javaUrl: 'http://java:8080', apiKey: 'test-key' }, {
        async post(url, body, options) { request = { url, body, options }; return { data: { response: 'Resposta' } }; }
    });
    assert.equal(await process({ text: 'Oi', contact: { id: 'contact-id' } }), 'Resposta');
    assert.equal(request.url, 'http://java:8080/api/v1/chat');
    assert.deepEqual(request.body, { message: 'Oi' });
    assert.equal(request.options.headers['X-Ayla-WhatsApp-Chat'], 'contact-id');
    assert.equal(request.options.headers.Authorization, 'Bearer test-key');
    assert.equal(formatReply(HEADER + '\nResposta'), HEADER + '\n\nResposta');
});

test('management HTTP endpoints require a key, expose QR without caching and support chat CRUD', async t => {
    const f = fixture(), key = 'k'.repeat(36);
    f.repository.list = async () => [...f.contacts.values()];
    f.repository.remove = async id => { for (const [chatId, row] of f.contacts) if (row.id === id) f.contacts.delete(chatId); };
    const app = createApp({ manager: f.manager, repository: f.repository, apiKey: key });
    const server = app.listen(0, '127.0.0.1'); await once(server, 'listening');
    t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
    const base = `http://127.0.0.1:${server.address().port}`;
    assert.equal((await fetch(base + '/qr')).status, 401);
    const headers = { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' };
    const qr = await fetch(base + '/qr', { headers });
    assert.equal(qr.status, 200); assert.equal(qr.headers.get('cache-control'), 'no-store');
    const created = await fetch(base + '/chats', { method: 'POST', headers, body: JSON.stringify({ chatId: '5511999999999@c.us', name: 'Contato' }) });
    assert.equal(created.status, 201); const row = await created.json();
    assert.equal((await (await fetch(base + '/chats', { headers })).json()).length, 1);
    assert.equal((await fetch(base + '/chats/' + row.id, { method: 'PUT', headers, body: JSON.stringify({ enabled: false }) })).status, 200);
    assert.equal((await fetch(base + '/chats/' + row.id, { method: 'DELETE', headers })).status, 200);
    assert.equal((await (await fetch(base + '/chats', { headers })).json()).length, 0);
    assert.equal((await fetch(base + '/chats', { method: 'POST', headers, body: '{"userId":"other"}' })).status, 400);
});

test('new authorized photos join captions/text after ten seconds and reach Java; old/unauthorized/failed images never reach AI', async () => {
    const timers = clock(), f = fixture({ batchDelayMs: 10000, batchTimers: timers });
    const data = Buffer.from([255, 216, 255, 224, 0, 0]).toString('base64');
    let downloads = 0, request;
    const photo = id => ({ ...f.message(id, ''), type: 'image', hasMedia: true,
        async downloadMedia() { downloads++; return { mimetype: 'image/jpeg', data }; } });
    await f.manager.enqueue(photo('unauthorized'));
    assert.equal(downloads, 0);
    await f.manager.enqueue(f.message('enable-photo', 'configurar ayla0101'));
    await f.manager.enqueue({ ...photo('receipt'), body: 'Minha nota', hasMedia: false });
    assert.equal(downloads, 0); // Partial media must not be processed as a text-only expense.
    f.manager.processor = createMessageProcessor({ javaUrl: 'http://java:8080', apiKey: 'test-key' }, {
        async post(url, body) { request = body; f.calls.push(body); return { data: { response: 'Analisei sua imagem.' } }; },
    });
    await f.manager.enqueue({ ...photo('old-photo'), timestamp: f.manager.readySince - 1 });
    await f.manager.enqueue(photo('receipt'));
    await f.manager.enqueue(photo('receipt'));
    assert.equal(downloads, 1);
    await timers.tick(9000);
    await f.manager.enqueue(f.message('caption-after-photo', 'É a nota do meu almoço'));
    await timers.tick(9999); assert.equal(f.calls.length, 0);
    await timers.tick(1);
    assert.equal(request.message, 'É a nota do meu almoço');
    assert.deepEqual(request.images, [{ mimeType: 'image/jpeg', data }]);
    await f.manager.enqueue({ ...photo('failed-photo'), async downloadMedia() { return undefined; } });
    await timers.tick(10000);
    assert.equal(f.calls.length, 1);
    assert.match(f.sent.at(-1).text, /reenviar como foto/);
});

test('new authorized voice notes join text after ten quiet seconds and reach Java without reading old audio', async () => {
    const timers=clock(),f=fixture({batchDelayMs:10000,batchTimers:timers});
    const data=Buffer.from('OggS-voice-note').toString('base64');let downloads=0,request;
    const voice=id=>({...f.message(id,''),type:'ptt',hasMedia:true,
        async downloadMedia(){downloads++;return {mimetype:'audio/ogg; codecs=opus',data};}});
    await f.manager.enqueue(voice('unauthorized-voice'));assert.equal(downloads,0);
    await f.manager.enqueue(f.message('enable-voice','configurar ayla0101'));
    f.manager.processor=createMessageProcessor({javaUrl:'http://java:8080',apiKey:'test-key'}, {
        async post(url,body){request=body;return {data:{response:'Entendi seu áudio.'}};}
    });
    await f.manager.enqueue({...voice('old-voice'),timestamp:f.manager.readySince-1});
    await f.manager.enqueue(voice('new-voice'));await f.manager.enqueue(voice('new-voice'));
    await timers.tick(9000);await f.manager.enqueue(f.message('voice-context','Sobre o café'));
    await timers.tick(9999);assert.equal(request,undefined);await timers.tick(1);
    assert.equal(downloads,1);assert.equal(request.message,'Sobre o café');
    assert.deepEqual(request.audios,[{mimeType:'audio/ogg',data}]);
});

test('explicit audio transcription failure asks to resend; unknown failures retain uncertain-operation handling', async () => {
    for (const known of [true,false]) {
        const error=Object.assign(new Error('HTTP 503'),{response:{status:503,data:{provider:'hermes',response:known?
            'Não consegui autenticar a transcrição no Hermes.':'O Hermes não concluiu a resposta.'}}});
        const process=createMessageProcessor({javaUrl:'http://java:8080',apiKey:'test'}, {async post(){throw error;}});
        await assert.rejects(()=>process({text:'',contact:{id:'contact'},audios:[{mimeType:'audio/ogg',data:'T2dnUw=='}]}), e=>{
            assert.equal(e,error);
            if(known) {assert.match(e.userMessage,/transcrever/);assert.doesNotMatch(e.userMessage,/lançamento/);}
            else assert.equal(e.userMessage,undefined);
            return true;
        });
    }
});

test('voice reply sends only header plus audio, falls back to text on failure, sends native audio once and ignores its own voice echo before send resolves', async () => {
    const f=fixture();await f.manager.enqueue(f.message('enable-speaking','configurar ayla0101'));
    const audio={mimeType:'audio/ogg',data:Buffer.from('OggS-ayla-voice').toString('base64')};
    let aiCalls=0;
    f.manager.processor=createMessageProcessor({javaUrl:'http://java:8080',apiKey:'test'}, {
        async post(){aiCalls++;return {data:{response:'Oi! Tudo bem?',audio}};}
    });
    const original=f.client.sendMessage.bind(f.client);
    f.client.sendMessage=async (chatId,content,options)=>{
        await original(chatId,content,options);
        if(typeof content!=='string') {
            assert.equal(options.sendAudioAsVoice,true);assert.equal(options.caption,undefined);
            const echo={...f.message('outgoing-voice','',f.client.info.wid._serialized,true),type:'ptt',hasMedia:true,
                rawData:{filehash:require('node:crypto').createHash('sha256').update(Buffer.from(audio.data,'base64')).digest('base64')},
                async downloadMedia(){throw new Error('Own voice must not be downloaded');}};
            await f.manager.enqueue(echo);
            return {id:{_serialized:'outgoing-voice'}};
        }
    };
    await f.manager.enqueue(f.message('ask-speaking','Me manda um áudio'));
    assert.equal(aiCalls,1);assert.equal(f.sent.length,3);
    assert.equal(f.sent[1].text,HEADER);
    assert.equal(f.sent[2].text.data,audio.data);
    // Delivery failure is independent of the already delivered text/financial result.
    f.client.sendMessage=async (chatId,content,options)=>{
        if(typeof content!=='string') throw new Error('voice transport unavailable');
        return original(chatId,content,options);
    };
    await f.manager.enqueue(f.message('ask-again','Responda por áudio'));
    assert.equal(aiCalls,2);assert.equal(f.sent.length,5);
    assert.equal(f.sent.at(-1).text,`${HEADER}\n\nOi! Tudo bem?`);
});

test('text replies include the header exactly once and immediate own-number echoes are ignored before send resolves',async()=>{
    const f=fixture();await f.manager.enqueue(f.message('enable-plain','configurar ayla0101'));
    const original=f.client.sendMessage.bind(f.client);
    f.client.sendMessage=async (chatId,text,options)=>{
        await original(chatId,text,options);
        await f.manager.enqueue({...f.message('immediate-plain-echo',text,f.client.info.wid._serialized,true),to:chatId});
    };
    await f.manager.enqueue(f.message('plain-question','Oi, Ayla'));
    assert.equal(f.calls.length,1);assert.equal(f.sent.at(-1).text,`${HEADER}\n\nOi, tudo bem?`);
    assert.equal(f.sent.at(-1).text.startsWith(`${HEADER}\n\n${HEADER}`),false);
});

test('only the owner can invoke in an unconfigured chat; calls stay mention-only and context is never processed independently', async () => {
    const f=fixture(), reads=[];
    f.client.readInvocationContext=async (...args)=>{reads.push(args);return {messages:[{id:{_serialized:'old'},body:'Comprar arroz',fromMe:false,type:'chat'}],quoted:null};};
    await f.manager.enqueue(f.message('other-enable','configurar ayla0101','5511999999999@c.us',false));
    await f.manager.enqueue(f.message('other-call','Ayla0101 anote arroz','5511999999999@c.us',false));
    assert.equal(f.contacts.size,0);assert.equal(reads.length,0);assert.equal(f.calls.length,0);
    await f.manager.enqueue(f.message('own-call','Ayla0101 Anote esses itens na lista de compras','5511999999999@c.us',true));
    assert.equal(f.calls.length,1);assert.equal(reads[0][3],10);
    assert.equal(f.contacts.values().next().value.mentionOnly,true);
    assert.equal(f.calls[0].whatsappContext.messages[0].text,'Comprar arroz');
    assert.equal(f.calls[0].text,'Anote esses itens na lista de compras');
    await f.manager.enqueue(f.message('ordinary','Mensagem comum','5511999999999@c.us',false));
    await f.manager.enqueue(f.message('own-ordinary','Mensagem comum','5511999999999@c.us',true));
    assert.equal(f.calls.length,1);
    await f.manager.enqueue(f.message('more','Leia as últimas 20 mensagens Ayla0101','5511999999999@c.us',true));
    assert.equal(reads.at(-1)[3],20);assert.equal(f.calls.length,2);
    const old=f.message('old-call','Ayla0101 ler mensagens','5511999999999@c.us',true);old.timestamp=f.manager.readySince-10;
    await f.manager.enqueue(old);assert.equal(f.calls.length,2);
});

test('owner quote selects a third-party photo/document without replaying historical commands', async () => {
    for(const kind of ['image','document']) {
        const f=fixture(), group='120363000000000000@g.us';
        const quoted={id:{_serialized:'quoted'},body:'conteúdo de terceiro',fromMe:false,type:kind,hasMedia:true,
            rawData:{filename:'lista.pdf',mimetype:kind==='image'?'image/jpeg':'application/pdf'}};
        f.client.readInvocationContext=async()=>({messages:[{id:{_serialized:'old-command'},body:'configurar ayla0101',fromMe:false}],quoted});
        f.client.downloadImageMedia=async message=>{assert.equal(message.id._serialized,'quoted');return {mimetype:'image/jpeg',data:'/9j/2Q=='};};
        f.client.downloadDocumentMedia=async message=>{assert.equal(message.id._serialized,'quoted');return {mimetype:'application/pdf',data:Buffer.from('%PDF-1.7\nsynthetic').toString('base64'),filename:'lista.pdf'};};
        const request={...f.message('invoke',kind==='image'?'O que achou desta foto Ayla0101':'Analise esse documento Ayla0101',group,true),hasQuotedMsg:true};
        await f.manager.enqueue(request);
        assert.equal(f.calls.length,1);assert.equal(f.calls[0].whatsappContext.quoted.owner,false);
        assert.equal(f.calls[0].whatsappContext.quoted.text,'conteúdo de terceiro');
        assert.equal(f.calls[0].images.length,kind==='image'?1:0);
        assert.equal(f.calls[0].documents.length,kind==='document'?1:0);
        assert.equal(f.contacts.size,1);assert.equal(f.contacts.values().next().value.mentionOnly,true);
    }
});

test('all third-party input is ignored even in automatic chats; owner commands normalize case and whitespace',async()=>{
    const f=fixture(),group='120363000000000000@g.us';
    f.manager.config.commandToken='outro-codigo'; // Runtime alias is fixed, never supplied by message/config overrides.
    await f.manager.enqueue(f.message('enable','  CONFIGURAR   AyLa0101  ',group,true));
    assert.equal(f.contacts.get(group).enabled,true);
    let contextReads=0,mediaReads=0,reactions=0;
    f.client.readInvocationContext=async()=>{contextReads++;return {messages:[],quoted:null};};
    f.client.downloadImageMedia=async()=>{mediaReads++;throw new Error('must not download');};
    f.client.downloadAudioMedia=f.client.downloadImageMedia;
    f.client.downloadDocumentMedia=f.client.downloadImageMedia;
    f.client.sendReaction=async()=>{reactions++;};
    const before=f.sent.length;
    for(const target of [group,'5511999999999@c.us']) {
        for(const text of ['Olá','Ayla0101 anote arroz','ayla0101','CONFIGURAR AYLA0101','tirar ayla0101'])
            await f.manager.enqueue(f.message('third-'+target+'-'+text,text,target,false));
        for(const type of ['image','ptt','document'])
            await f.manager.enqueue({...f.message('third-'+target+'-'+type,'Ayla0101 analise',target,false),type,hasMedia:true});
        await f.manager.enqueue({...f.message('untrusted-identity-'+target,'Ayla0101',target,false),fromMe:'true'});
    }
    assert.equal(f.calls.length,0);assert.equal(f.sent.length,before);
    assert.equal(contextReads,0);assert.equal(mediaReads,0);assert.equal(reactions,0);
    assert.equal(f.contacts.get(group).enabled,true);
    await f.manager.enqueue(f.message('owner-normal','Oi, Ayla',group,true));
    assert.equal(f.calls.length,1);
    await f.manager.enqueue(f.message('owner-disable',' TIRAR   AYLA0101 ',group,true));
    assert.equal(f.contacts.get(group).enabled,false);
    await f.manager.enqueue(f.message('third-after-disable','Ayla0101',group,false));
    assert.equal(f.contacts.get(group).enabled,false);
});

test('password recovery is server-only, targets the connected owner and never stores/sends the secret to the agent',async t=>{
    const f=fixture(),key='k'.repeat(36),password='r'.repeat(24),sensitive=[],finishes=[];
    f.manager.config.ownerId='local-owner';
    f.repository.instance=async()=>({owner_user_id:'local-owner',connected_number:f.client.info.wid.user});
    f.repository.markSensitive=async(id,chat)=>sensitive.push({id,chat});
    f.repository.finish=async(...args)=>finishes.push(args);
    f.client.sendMessage=async(chatId,text,options)=>{
        f.sent.push({chatId,text,options});
        await f.manager.enqueue(f.message('private-echo',text,chatId,true));
        return {id:{_serialized:'private-recovery'}};
    };
    const server=createApp({manager:f.manager,repository:f.repository,apiKey:key}).listen(0,'127.0.0.1');
    await once(server,'listening');t.after(()=>{server.closeAllConnections();return new Promise(resolve=>server.close(resolve));});
    const url=`http://127.0.0.1:${server.address().port}/internal/password-recovery`;
    const payload={userId:'local-owner',registeredNumber:f.client.info.wid.user,password,requestId:'00000000-0000-4000-8000-000000000001'};
    const request=(body,authorize=true)=>fetch(url,{method:'POST',headers:{'Content-Type':'application/json',...(authorize?{Authorization:'Bearer '+key}:{})},body:JSON.stringify(body)});
    assert.equal((await request(payload,false)).status,401);
    assert.equal((await request({...payload,phone:'5511777777777'})).status,400);
    assert.equal((await request({...payload,userId:'other-user'})).status,403);
    assert.equal((await request({...payload,registeredNumber:'5511777777777'})).status,503);
    const response=await request(payload);assert.equal(response.status,200);
    assert.deepEqual(await response.json(),{sent:true});
    assert.equal(f.sent.length,1);assert.equal(f.sent[0].chatId,f.client.info.wid._serialized);
    assert.ok(f.sent[0].text.includes(password));assert.ok(f.sent[0].text.startsWith(HEADER));
    assert.deepEqual(sensitive,[{id:'private-recovery',chat:f.client.info.wid._serialized}]);
    assert.ok(finishes.every(args=>args.length<3));assert.equal(f.calls.length,0);
    assert.deepEqual(await (await request(payload)).json(),{sent:false,duplicate:true});
    assert.equal(f.sent.length,1);
});
