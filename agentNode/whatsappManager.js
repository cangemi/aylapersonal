const path = require('node:path');
const { createHash } = require('node:crypto');
const { sendVoiceFromPage } = require('./voiceMedia');
const { createMessageProcessor, formatReply, HEADER } = require('./messageProcessor');
const { installLiveMessages } = require('./liveMessages');
const { messageKey, wid } = require('./messageIdentity');
const { MessageBuffer } = require('./messageBuffer');
const { injectAfterNavigation } = require('./navigationRecovery');
const { ownerTypingInPage, installOwnerActivity } = require('./ownerActivity');
const { inspectPersonalGroupInPage } = require('./personalGroupRemoval');
const { prepareGroupCreation, personalGroupStateInPage, findPersonalGroupInPage } = require('./groupCreation');
const { applyGroupPicture } = require('./groupPicture');
const { reportWhatsappFailure } = require('./monitoring');
const { isImage, downloadImage, downloadImageFromPage, isAudio, downloadAudio, downloadAudioFromPage,isDocument,downloadDocument } = require('./imageMedia');
const { invocation,readContextFromPage,contextView,COMMAND_TOKEN,normalizeCommand,RECOVERY_PREFIX } = require('./invocation');

function defaultClient(config, store) {
    const { Client, RemoteAuth } = require('whatsapp-web.js');
    class DatabaseAuth extends RemoteAuth {
        async storeRemoteSession(options) {
            try { await super.storeRemoteSession(options); }
            catch (error) { this.client.emit('remote_session_error', error); }
        }
    }
    class LiveClient extends Client {
        async inject() {return injectAfterNavigation(() => super.inject(), () => this.pupPage && !this.pupPage.isClosed());}
        async findPersonalGroup() {return this.pupPage.evaluate(findPersonalGroupInPage);}
        async personalGroupStatus(chatId) {
            return this.pupPage.evaluate(personalGroupStateInPage,chatId);
        }
        async setPersonalGroupPicture(chatId) {
            const {MessageMedia}=require('whatsapp-web.js');
            const media=MessageMedia.fromFilePath(path.join(__dirname,'assets','AylaChat.png'));
            // Same operation as GroupChat.setPicture, without serializing group participants.
            return applyGroupPicture(this.pupPage,chatId,media);
        }
        async createGroup(...args) {
            await prepareGroupCreation(this.pupPage);
            return super.createGroup(...args);
        }
        async sendVoiceMedia(chatId,audio) {return sendVoiceFromPage(this.pupPage,chatId,audio);}
        async downloadImageMedia(message) {
            return downloadImageFromPage(this.pupPage, messageKey(message));
        }
        async downloadAudioMedia(message) { return downloadAudioFromPage(this.pupPage, messageKey(message)); }
        async downloadDocumentMedia(message) {return downloadImageFromPage(this.pupPage,messageKey(message),'document');}
        async readInvocationContext(...args) {return readContextFromPage(this.pupPage,...args);}
        async setChatTyping(chatId, state) {
            return this.pupPage.evaluate((id, value) => window.WWebJS.sendChatstate(value, id), chatId, state);
        }
        async getChatLabel(chatId) {
            // Use only the already loaded label. getChatById serializes group
            // participants and can fail on WhatsApp's IndexedDB migration.
            return this.pupPage.evaluate(id => {
                const chat = window.require('WAWebCollections').Chat.get(id);
                return chat?.name || chat?.formattedTitle || null;
            }, chatId);
        }
        async attachEventListeners() {
            await super.attachEventListeners();
            await installLiveMessages(this);
            await installOwnerActivity(this).catch(()=>{});
        }
        async isOwnerTyping(chatId) {return this.pupPage.evaluate(ownerTypingInPage,chatId);}
        async inspectPersonalGroup(chatId,leave=false) {return this.pupPage.evaluate(inspectPersonalGroupInPage,chatId,leave);}
        async deletePersonalGroupChat(chatId) {return this.pupPage.evaluate(id=>window.WWebJS.sendDeleteChat(id),chatId);}
    }
    return new LiveClient({
        authStrategy: new DatabaseAuth({ clientId: config.instanceId, dataPath: config.authDir, store, backupSyncIntervalMs: config.backupInterval }),
        puppeteer: { headless: true, executablePath: config.browserPath,
            args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'] },
        webVersionCache: { type: 'local', path: path.join(config.authDir, 'web-cache') },
        qrMaxRetries: 8,
    });
}
class WhatsappManager {
    constructor({ config, repository, store, clientFactory = defaultClient, processor, renderQR, logger = console, batchTimers }) {
        this.config = config; this.repository = repository; this.store = store;
        this.clientFactory = clientFactory; this.processor = processor || createMessageProcessor(config);
        this.renderQR = renderQR || (text => require('qrcode').toDataURL(text));
        this.log = logger; this.client = null; this.phase = 'disconnected'; this.qr = null;
        this.queues = new Map(); this.lifecycle = Promise.resolve(); this.reconnectTimer = null;
        this.stopping = false; this.qrVersion = 0;
        this.readySince = null;
        this.connectionEpoch = 0;
        this.responseQueues = new Map();
        this.submissionQueues = new Map();
        this.outgoingVoiceHashes = new Set();
        this.outgoingTextHashes = new Map();
        this.processingReactions = new Set();
        this.recoveryTimer = null;
        this.buffers = new MessageBuffer({ delayMs: config.batchDelayMs ?? 5000,
            isTyping:chatId=>this.client?.isOwnerTyping?.(chatId)??false,
            onFlush: batch => this.queueBatch(batch), timers: batchTimers });
    }
    control(operation) {
        const work = this.lifecycle.catch(() => {}).then(operation);
        this.lifecycle = work;
        return work;
    }
    async status() {
        const saved = await this.repository.instance();
        return { instanceId: this.config.instanceId, state: this.phase, connected: this.phase === 'ready',
            desiredConnected: saved.desired_connected, number: saved.connected_number, name: saved.connected_name,
            sessionSaved: Boolean(saved.session_saved_at), sessionSavedAt: saved.session_saved_at || null,
            lastError: saved.last_error || null, updatedAt: saved.updated_at, personalChatId: saved.personal_group_chat_id || null };
    }
    getQR() { return { state: this.phase, qr: this.qr?.raw || null, qrImageDataUrl: this.qr?.image || null,
        qrAscii: this.qr?.ascii || null, generatedAt: this.qr?.generatedAt || null }; }
    async createPersonalChat() {
        return this.control(async () => {
            if(this.phase!=='ready' || !this.client)
                throw Object.assign(new Error('Conecte seu WhatsApp antes de criar a conversa.'),{status:409});
            const saved=await this.repository.instance();
            let chatId=saved.personal_group_chat_id || this.personalGroupId;
            let created=false;
            if(chatId) {
                const groupState=await this.client.personalGroupStatus(chatId);
                if(['terminated_local','terminated','not_member'].includes(groupState)) {
                    const previous=await this.repository.find(chatId,null);
                    if(previous) await this.repository.update(previous.id,{enabled:false,mentionOnly:true});
                    await this.repository.savePersonalGroup(null);
                    this.personalGroupId=null;chatId=null;
                    this.log.log('[whatsapp] grupo pessoal encerrado ou sem participação; preparando substituto.');
                } else if(groupState!=='success') {
                    throw Object.assign(new Error('Não foi possível verificar o grupo pessoal agora.'),{status:503});
                }
            }
            if(!chatId) chatId=await this.client.findPersonalGroup();
            if(!chatId) {
                // Empty participant list: only the connected account creates/joins this group.
                // Never invite another number or send invitation messages.
                let result;
                try {result=await this.client.createGroup('Ayla',[],{autoSendInviteV4:false});}
                catch(error) {
                    this.log.error('[whatsapp] criação do grupo pessoal falhou:',{stage:'carregar/criar grupo',type:error.name||'Error',detail:String(error.message||'').replace(/\d{8,}/g,'[id]').slice(0,300)});
                    const recovered=await this.client.findPersonalGroup();
                    if(!recovered)throw error;
                    result={gid:recovered};
                }
                chatId=wid(result?.gid);
                if(!/^\d+(?:-\d+)?@g\.us$/.test(chatId||'')) {
                    this.log.error('[whatsapp] criação do grupo pessoal não confirmada:',{stage:'validar grupo',resultType:typeof result,libraryError:typeof result==='string'&&result.startsWith('CreateGroupError')});
                    throw Object.assign(new Error('Não foi possível confirmar a criação. Confira se o grupo Ayla apareceu no WhatsApp antes de tentar novamente.'),{status:503});
                }
                this.personalGroupId=chatId;
                created=true;
            }
            // Save the destination first. If authorization fails, retry reuses the same group.
            await this.repository.savePersonalGroup(chatId);
            const chat=await this.repository.authorize({chatId,name:'Ayla',enabled:true,mentionOnly:false},'personal-chat');
            let pictureUpdated=false;
            try {pictureUpdated=await this.client.setPersonalGroupPicture(chatId)===true;}
            catch(error) {this.log.error('[whatsapp] foto do grupo pessoal não atualizada:',{stage:'foto do grupo',type:error.name||'Error',detail:String(error.message||'').replace(/\d{8,}/g,'[id]').slice(0,300)});}
            return {chat,created,pictureUpdated};
        });
    }
    async start() {
        const saved = await this.repository.init();
        if (saved.desired_connected) await this.connect();
        else await this.repository.state('disconnected', false);
    }
    async connect() {
        return this.control(async () => {
            if (this.client) return this.status();
            this.stopping = false; clearTimeout(this.reconnectTimer);
            const active = this.clientFactory(this.config, this.store);
            this.client = active; this.phase = 'connecting'; this.qr = null;
            this.readySince = null;
            await this.repository.state('connecting', true);
            const observe = fn => (...args) => {
                if (this.client !== active) return;
                Promise.resolve().then(() => fn(...args)).catch(error => this.log.error('[whatsapp] evento falhou:', error.code || error.name));
            };
            active.on('qr', observe(async raw => {
                const version = ++this.qrVersion;
                this.phase = 'awaiting_qr'; this.qr = { raw, generatedAt: new Date().toISOString(), image: null, ascii: null };
                await this.repository.state('awaiting_qr', true);
                require('qrcode-terminal').generate(raw, { small: true }, ascii => {
                    if (this.client === active && version === this.qrVersion) { this.qr.ascii = ascii; this.log.log(ascii); }
                });
                const image = await this.renderQR(raw);
                if (this.client === active && version === this.qrVersion && this.qr) this.qr.image = image;
            }));
            active.on('authenticated', observe(async () => {
                this.phase = 'authenticated'; this.qr = null; ++this.qrVersion;
                await this.repository.state('authenticated', true);
            }));
            active.on('ready', observe(async () => {
                this.cancelBuffered();
                ++this.connectionEpoch;
                // WhatsApp timestamps have second precision. Exclude the boundary
                // second too, so pre-ready messages can never slip through sync.
                this.readySince = Math.floor(Date.now() / 1000);
                this.phase = 'ready'; this.qr = null;
                await this.repository.state('ready', true, { number: active.info?.wid?.user, name: active.info?.pushname });
                this.log.log('[whatsapp] conectado; backup inicial da sessão pode levar cerca de um minuto.');
                if (!this.recoveryTimer && this.processor.recover) {
                    this.recoveryTimer = setInterval(() => { void this.recoverQueuedReplies(); }, 5000);
                    this.recoveryTimer.unref?.();
                    void this.recoverQueuedReplies();
                }
            }));
            active.on('change_state', state => {
                if (this.client !== active || !['ready', 'reconnecting'].includes(this.phase)) return;
                if (state === 'CONNECTED') {
                    if (this.phase !== 'reconnecting') return;
                    this.readySince = Math.floor(Date.now() / 1000);
                    this.phase = 'ready';
                } else {
                    this.cancelBuffered();
                    this.readySince = null;
                    this.phase = 'reconnecting';
                }
                ++this.connectionEpoch;
                this.repository.state(this.phase, true).catch(error => this.log.error('[whatsapp] estado não salvo:', error.code || error.name));
            });
            active.on('remote_session_saved', observe(async () => {
                await this.repository.state(this.phase, true, { number: active.info?.wid?.user, name: active.info?.pushname });
                this.log.log('[whatsapp] sessão persistida no PostgreSQL.');
            }));
            active.on('remote_session_error', observe(async () => {
                await this.repository.state(this.phase, null, { number: active.info?.wid?.user, name: active.info?.pushname,
                    error: 'Não foi possível atualizar o backup da sessão no PostgreSQL.' });
                this.log.error('[whatsapp] falha ao salvar sessão no PostgreSQL.');
            }));
            active.on('auth_failure', observe(async () => {
                this.phase = 'auth_failure'; this.qr = null;
                await this.repository.state('auth_failure', false, { error: 'Autenticação recusada. Use logout e conecte novamente.' });
            }));
            active.on('disconnected', observe(async reason => {
                this.cancelBuffered();
                this.client = null; this.phase = 'disconnected'; this.qr = null; ++this.qrVersion;
                await active.destroy().catch(() => {});
                const loggedOut = reason === 'LOGOUT';
                if (loggedOut) await this.store.delete({ session: this.store.sessionName });
                await this.repository.state('disconnected', loggedOut ? false : null);
                if (!loggedOut && !this.stopping) this.reconnectTimer = setTimeout(() => this.connect().catch(() => {}), 5000);
            }));
            active.on('message_create', message => {
                // Check at arrival, before any asynchronous work or chat queue.
                if (this.client !== active) return;
                this.enqueue(message).catch(error => this.log.error('[whatsapp] evento falhou:', error.code || error.name));
            });
            active.on('ayla_owner_activity',event=>{
                if(this.client===active && this.phase==='ready')this.buffers.typing(event.chatId,event.active);
            });
            this.initializing = active.initialize().then(async () => {
                if (this.client !== active) await active.destroy().catch(() => {});
            }).catch(async error => {
                if (this.client !== active) { await active.destroy().catch(() => {}); return; }
                this.client = null; this.phase = 'error'; this.qr = null;
                await active.destroy().catch(() => {});
                await this.repository.state('error', null, { error: 'Falha ao iniciar WhatsApp. Verifique Chromium, rede e banco; tente conectar novamente.' });
                this.log.error('[whatsapp] inicialização falhou:', error.code || error.name);
            });
            return this.status();
        });
    }
    async stop({ logout = false, preserveDesired = false } = {}) {
        clearInterval(this.recoveryTimer); this.recoveryTimer = null;
        this.cancelBuffered();
        this.stopping = true; clearTimeout(this.reconnectTimer);
        await Promise.allSettled([...this.processingReactions].map(clear => clear()));
        const active = this.client;
        this.readySince = null;
        this.client = null; this.qr = null; ++this.qrVersion;
        await this.repository.state('disconnecting', preserveDesired ? null : false);
        let failure;
        if (active) {
            try {
                if (logout && active.pupPage) await active.logout();
                else if (this.phase === 'ready' && await this.store.sessionExists({ session: this.store.sessionName }))
                    await active.authStrategy.storeRemoteSession();
            } catch (error) { failure = error; }
            finally { await active.destroy().catch(() => {}); }
        }
        if (logout) await this.store.delete({ session: this.store.sessionName });
        this.phase = logout ? 'logged_out' : 'disconnected';
        await this.repository.state(this.phase, preserveDesired ? null : false,
            failure ? { error: logout ? 'Sessão local apagada; não foi possível confirmar logout remoto no WhatsApp.' : 'Conexão encerrada; último backup disponível preservado.' } : {});
        return this.status();
    }
    disconnect() { return this.control(() => this.stop()); }
    logout() { return this.control(() => this.stop({ logout: true })); }
    restart() { return this.disconnect().then(() => this.connect()); }
    shutdown() { return this.control(() => this.stop({ preserveDesired: true })); }
    isNewMessage(message) {
        return this.client && this.phase === 'ready' && this.readySince !== null
            && Number.isFinite(message.timestamp) && message.timestamp > this.readySince;
    }
    enqueue(message) {
        // Native sender identity is mandatory in every chat, including automatic chats.
        // Never trust text, display names, a mention or a quoted author as authentication.
        if (message.fromMe !== true) return Promise.resolve();
        // Processing reactions must never become a new conversation turn.
        if (message.type === 'reaction' || message.rawData?.type === 'reaction') return Promise.resolve();
        // Register hash BEFORE sending: local message_create can arrive before sendMessage resolves.
        if (message.fromMe && this.outgoingVoiceHashes.has(message.rawData?.filehash)) return Promise.resolve();
        if (!this.isNewMessage(message)) {
            if (/^configurar\s+/i.test(String(message.body || '').trim()))
                this.log.log('[whatsapp] comando ignorado:', this.phase !== 'ready' ? 'conexão ainda não pronta' : 'mensagem anterior à conexão ou sem data válida');
            return Promise.resolve();
        }
        const receivingClient = this.client, readySince = this.readySince, epoch = this.connectionEpoch;
        const chatId = wid(message.fromMe ? message.to : message.from) || wid(message.id?.remote);
        if (!chatId || message.isStatus || /@(?:broadcast|newsletter)$/.test(chatId)) return Promise.resolve();
        const echoKey=chatId+':'+createHash('sha256').update(String(message.body||'').trim()).digest('hex');
        if(message.fromMe && (this.outgoingTextHashes.get(echoKey)||0)>Date.now()) return Promise.resolve();
        const work = (this.queues.get(chatId) || Promise.resolve()).catch(() => {}).then(() => {
            if (this.client !== receivingClient || this.connectionEpoch !== epoch || this.readySince !== readySince || !this.isNewMessage(message)) return;
            return this.handle(message, chatId, receivingClient, epoch);
        });
        this.queues.set(chatId, work);
        work.finally(() => { if (this.queues.get(chatId) === work) this.queues.delete(chatId); }).catch(() => {});
        return work;
    }
    async handle(message, chatId, receivingClient, epoch) {
        if (message.fromMe !== true) return;
        const messageId = messageKey(message);
        const body = String(message.body || '').trim();
        const image = isImage(message);
        const audio = isAudio(message);
        const document = isDocument(message);
        if ((image || audio || document) && !message.hasMedia) return;
        if (!messageId || (!body && !image && !audio && !document) || (body.startsWith(HEADER) || String(message.rawData?.caption || '').startsWith(HEADER)) || !this.client || this.phase !== 'ready') return;
        if (!message.id._serialized) message.id = { ...message.id, _serialized: messageId };
        const normalized = normalizeCommand(body);
        const token = COMMAND_TOKEN;
        const command = normalized === `configurar ${token}` ? 'enable' : normalized === `tirar ${token}` ? 'disable' : null;
        const called=command?null:invocation(body,token);
        if (!await this.repository.claim(messageId, chatId)) return;
        let stage = 'consultar autorização';
        try {
            if ((command || called) && message.fromMe !== true) {await this.repository.finish(messageId,'ignored');return;}
            if(this.repository.accountEnabled&&!await this.repository.accountEnabled()){await this.repository.finish(messageId,'ignored');return;}
            let phone = chatId.endsWith('@c.us') ? chatId.split('@')[0] : null;
            if (chatId.endsWith('@lid') && this.client.getContactLidAndPhone) {
                const mapped = await this.client.getContactLidAndPhone([chatId]);
                phone = mapped?.[0]?.pn?.split('@')[0] || null;
            }
            let contact = await this.repository.find(chatId, phone);
            if (command === 'enable') {
                let label;
                try { label = await receivingClient.getChatLabel?.(chatId); } catch {}
                stage = 'salvar autorização';
                contact = await this.repository.authorize({ chatId, phone, name: label || phone || (chatId.endsWith('@g.us') ? 'Grupo WhatsApp' : 'Conversa WhatsApp'),mentionOnly:false }, message.author || message.from);
                this.log.log('[whatsapp] conversa autorizada:', contact.isGroup || chatId.endsWith('@g.us') ? 'grupo' : 'individual', 'origem:', message.fromMe ? 'próprio número' : 'contato');
                stage = 'enviar confirmação';
                await this.send(chatId, 'Esta é nossa conversa principal agora. Pode falar comigo por aqui!', messageId);
                return;
            }
            if (command === 'disable') {
                if (!contact) { await this.repository.finish(messageId, 'ignored'); return; }
                stage = 'desativar autorização';
                await this.repository.update(contact.id, { enabled: false });
                this.cancelBuffered(chatId);
                await this.send(chatId, 'Ayla desativada nesta conversa.', messageId);
                return;
            }
            if(called && !contact?.enabled) {
                let label;try {label=await receivingClient.getChatLabel?.(chatId);}catch {}
                contact=await this.repository.authorize({chatId,phone,name:label||phone||(chatId.endsWith('@g.us')?'Grupo WhatsApp':'Conversa WhatsApp'),mentionOnly:true},wid(receivingClient.info?.wid)||'owner');
            }
            const pending=this.buffers.pending.get(chatId);
            const continuation=message.fromMe===true && pending?.messages.some(m=>m.invocation);
            if (!contact?.enabled || (contact.mentionOnly && !called && !continuation)) { await this.repository.finish(messageId, 'ignored'); return; }
            stage = audio ? 'baixar áudio' : document?'baixar documento':'baixar imagem';
            const images = image ? [await downloadImage(message, receivingClient)] : [];
            const audios = audio ? [await downloadAudio(message, receivingClient)] : [];
            const documents = document ? [await downloadDocument(message,receivingClient)] : [];
            if (this.client !== receivingClient || this.connectionEpoch !== epoch || this.phase !== 'ready') {
                await this.repository.finish(messageId, 'cancelled'); return;
            }
            await this.repository.finish(messageId, 'buffered');
            return this.buffers.add({ chatId, contact, receivingClient, epoch }, { id: messageId,owner:true, body:called?called.text:body, images, audios,documents,
                quotedMessage:message.hasQuotedMsg?{id:messageId,hasQuotedMsg:true}:null,
                invocation:called?{...called,id:messageId,hasQuotedMsg:Boolean(message.hasQuotedMsg)}:null });
        } catch (error) {
            await this.failMessages(error, stage, chatId, [messageId], receivingClient, epoch);
        }
    }
    cancelBuffered(chatId) {
        const messages = chatId ? this.buffers.cancel(chatId) : this.buffers.clear();
        for (const message of messages) {
            message.images = []; message.audios = [];message.documents=[];
            this.repository.finish(message.id, 'cancelled').catch(() => {});
        }
    }
    queueBatch(batch) {
        const previousSubmission = this.submissionQueues.get(batch.chatId) || Promise.resolve();
        const previousDelivery = this.responseQueues.get(batch.chatId) || Promise.resolve();
        let admitted, finished;
        const admission = previousSubmission.catch(() => {}).then(() => new Promise(resolve => { admitted = resolve; }));
        const done = new Promise(resolve => { finished = resolve; });
        const deliveries = previousDelivery.catch(() => {}).then(() => done);
        this.submissionQueues.set(batch.chatId, admission);
        this.responseQueues.set(batch.chatId, deliveries);
        const work = previousSubmission.catch(() => {}).then(() => this.processBatch({ ...batch,
            onQueued: () => admitted?.(), beforeDelivery: () => previousDelivery.catch(() => {}) }));
        work.finally(() => {
            admitted?.(); finished();
            if (this.submissionQueues.get(batch.chatId) === admission) this.submissionQueues.delete(batch.chatId);
            deliveries.finally(() => { if (this.responseQueues.get(batch.chatId) === deliveries) this.responseQueues.delete(batch.chatId); }).catch(() => {});
        }).catch(() => {});
        return work;
    }
    async recoverQueuedReplies() {
        if (this.stopping || this.phase !== 'ready' || !this.processor.recover || !this.repository.list) return;
        try {
            if (this.repository.accountEnabled && !await this.repository.accountEnabled()) return;
            for (const contact of await this.repository.list()) {
                if (!contact.enabled || this.responseQueues.has(contact.chatId)) continue;
                const work = (async () => {
                    let answer;
                    try {
                        answer = await this.processor.recover(contact);
                        if (!answer) return;
                        if (this.stopping || this.phase !== 'ready' || !(await this.repository.find(contact.chatId, null))?.enabled
                            || (this.repository.accountEnabled && !await this.repository.accountEnabled())) return;
                        await this.send(contact.chatId, answer, []);
                        await answer.acknowledge?.();
                    } catch (error) { this.log.error('[whatsapp] resposta pendente aguardando recuperação:', error.code || error.name); }
                    finally { answer?.release?.(); }
                })();
                this.responseQueues.set(contact.chatId, work);
                work.finally(() => { if (this.responseQueues.get(contact.chatId) === work) this.responseQueues.delete(contact.chatId); }).catch(() => {});
            }
        } catch (error) { this.log.error('[whatsapp] consulta de respostas pendentes indisponível:', error.code || error.name); }
    }
    async processBatch({ chatId, contact, receivingClient, epoch, messages, onQueued, beforeDelivery = async () => {} }) {
        let queuedAnswer;
        const ids = messages.map(m => m.id);
        const called=messages.map(m=>m.invocation).filter(Boolean).at(-1);
        const quotedMessage=messages.map(m=>m.quotedMessage).filter(Boolean).at(-1);
        const reactionId = ids.at(-1);
        const active = () => !this.stopping && this.client === receivingClient && this.connectionEpoch === epoch && this.phase === 'ready';
        const authorized = current => current?.enabled && (!current.mentionOnly||called) && current.chatId === contact.chatId && current.phone === contact.phone && String(current.historyResetAt||'') === String(contact.historyResetAt||'');
        let typingTimer, reactionStart, processingReaction = false, stage = 'consultar autorização do lote';
        const react = async emoji => {
            try {
                if (!reactionId || !receivingClient.sendReaction) return false;
                await receivingClient.sendReaction(reactionId, emoji);
                return true;
            } catch (error) {
                // An optional visual indicator must not block or repeat an AI operation.
                this.log.error('[whatsapp] reação de processamento indisponível:', error.name || 'Erro');
                return false;
            }
        };
        const clearReaction = async () => {
            if (!processingReaction) return;
            processingReaction = false;
            this.processingReactions.delete(clearReaction);
            await reactionStart;
            await react('');
        };
        const typing = async state => {
            try { if (active()) await receivingClient.setChatTyping?.(chatId, state); }
            catch { /* Presence is optional and must never block the conversation. */ }
        };
        try {
            if (messages.some(message=>message.owner!==true) || !active() || !authorized(await this.repository.get(contact.id))) {
                await Promise.all(ids.map(id => this.repository.finish(id, 'cancelled'))); return;
            }
            if(this.repository.accountEnabled&&!await this.repository.accountEnabled()) {
                await Promise.all(ids.map(id=>this.repository.finish(id,'cancelled')));return;
            }
            await typing('typing');
            typingTimer = setInterval(() => { void typing('typing'); }, 20000);
            typingTimer.unref?.();
            stage = 'consultar Ayla';
            if (!active()) {
                await Promise.all(ids.map(id => this.repository.finish(id, 'cancelled'))); return;
            }
            const input={text:messages.map(m=>m.body).filter(Boolean).join('\n'),images:messages.flatMap(m=>m.images||[]),
                audios:messages.flatMap(m=>m.audios||[]),documents:messages.flatMap(m=>m.documents||[]),contact};
            input.requestKey = createHash('sha256').update(ids.join('\n')).digest('hex');
            input.onQueued = () => {
                // PostgreSQL now owns the pending media; do not retain every queued image/audio in Node RAM.
                for (const message of messages) { message.images=[];message.audios=[];message.documents=[]; }
                input.images=[];input.audios=[];input.documents=[];
                onQueued?.();
            };
            if(called || quotedMessage) {
                stage='ler contexto solicitado';
                const selection=called?.hasQuotedMsg?called:quotedMessage||called;
                const contextLimit=called?.limit??0;
                let result;
                try {
                    const sensitive=await this.repository.sensitiveMessageIds?.(chatId)||[];
                    result=await receivingClient.readInvocationContext(chatId,ids.at(-1),selection.id,contextLimit,[...ids,...sensitive],selection.hasQuotedMsg);
                }
                catch(cause){throw Object.assign(new Error('Invocation context unavailable',{cause}),{userMessage:'Não consegui carregar o contexto dessa conversa. Tente citar a mensagem novamente.'});}
                if(result.sensitiveQuote)throw Object.assign(new Error('Protected access message'),{userMessage:'Essa mensagem contém dados de acesso e não é enviada para a IA.'});
                if(selection.hasQuotedMsg && !result.quoted)throw Object.assign(new Error('Quoted message unavailable'),{userMessage:'Não consegui acessar a mensagem citada. Pode encaminhar o conteúdo ou anexá-lo novamente?'});
                result.warning=[result.warning,called?.warning].filter(Boolean).join(' ');
                input.whatsappContext=contextView(result,contextLimit);
                let reference=result.quoted;
                if(!reference && !input.images.length && !input.audios.length && !input.documents.length && /\b(foto|imagem|pdf|documento|arquivo|audio|[áa]udio)\b/i.test(input.text)) {
                    const matching=(result.messages||[]).filter(m=>/\b(foto|imagem)\b/i.test(input.text)?isImage(m):/\b(pdf|documento|arquivo)\b/i.test(input.text)?isDocument(m):isAudio(m));
                    reference=matching.at(-1);
                }
                if(reference?.hasMedia) {
                    if(isImage(reference)&&!input.images.length) input.images.push(await downloadImage(reference,receivingClient));
                    else if(isAudio(reference)) {
                        input.whatsappContext.referenceAudio=input.audios.length===0;
                        input.whatsappContext.referenceAudioIndex=input.audios.length;
                        input.audios.push(await downloadAudio(reference,receivingClient));
                    }
                    else if(isDocument(reference)&&!input.documents.length) input.documents.push(await downloadDocument(reference,receivingClient));
                }
            }
            if(!active() || !authorized(await this.repository.get(contact.id))) {await Promise.all(ids.map(id=>this.repository.finish(id,'cancelled')));return;}
            processingReaction = Boolean(reactionId && receivingClient.sendReaction);
            if (processingReaction) {
                this.processingReactions.add(clearReaction);
                reactionStart = react('⏳');
                await reactionStart;
            }
            if(!active()) {await Promise.all(ids.map(id=>this.repository.finish(id,'cancelled')));return;}
            stage='consultar Ayla';
            const answer = await this.processor(input);
            queuedAnswer = answer;
            await beforeDelivery();
            if (!active() || !authorized(await this.repository.get(contact.id)) || (this.repository.accountEnabled&&!await this.repository.accountEnabled())) {
                await Promise.all(ids.map(id => this.repository.finish(id, 'cancelled'))); return;
            }
            stage = 'enviar resposta';
            await this.send(chatId, answer, ids);
            await answer?.acknowledge?.();
        } catch (error) {
            await beforeDelivery();
            await this.failMessages(error, stage, chatId, ids, receivingClient, epoch);
        } finally {
            queuedAnswer?.release?.();
            clearInterval(typingTimer);
            await clearReaction();
            await typing('stop');
            for (const message of messages) { message.images = []; message.audios = [];message.documents=[]; }
        }
    }
    async failMessages(error, stage, chatId, ids, receivingClient, epoch) {
        // Only a fixed stage code is sent: never message text, phone, media or exception payload.
        void reportWhatsappFailure(this.config,stage);
            const detail = String(error.message || error.name || 'Erro desconhecido')
                .replaceAll(this.config.apiKey || '\0', '[credencial]')
                .replace(/https?:\/\/\S+/g, '[url]').replace(/[0-9-]{5,}/g, '[id]').slice(0, 600);
            this.log.error('[whatsapp] mensagem não concluída:', JSON.stringify({ stage, type: error.name,
                code: error.response?.status || error.code, detail }));
            await Promise.all(ids.map(id => this.repository.finish(id, 'failed')));
            const contact = await this.repository.find(chatId, null);
            if (contact?.enabled && this.client === receivingClient && this.connectionEpoch === epoch && this.phase === 'ready'
                    && (!this.repository.accountEnabled||await this.repository.accountEnabled())) {
                // Never retry the AI automatically: the original turn may already have saved financial data.
                await this.send(chatId, error.userMessage || 'Não consegui concluir agora. Se houve um lançamento, consulte antes de tentar novamente.', ids).catch(() => {});
            }
    }
    async conversationContact(conversationId) {
        const rows=await this.repository.pool.query('select * from whatsapp_chats where instance_id=$1 and conversation_id=$2',[this.config.instanceId,conversationId]);
        if(rows.rows.length!==1)throw Object.assign(new Error('Conversa não encontrada'),{status:404});
        return rows.rows[0];
    }
    async conversationRemovalPlan(conversationId) {
        const contact=await this.conversationContact(conversationId);
        const candidate=contact.enabled&&!contact.mention_only&&contact.is_group;
        const info=candidate&&this.phase==='ready'&&this.client?await this.client.inspectPersonalGroup(contact.chat_id):null;
        const removeWhatsappGroup=Boolean(info?.eligible);
        return {removeWhatsappGroup,description:removeWhatsappGroup
            ?'A conversa será apagada na Ayla e o grupo principal, que contém somente o seu número, será removido do seu WhatsApp.'
            :'A conversa será apagada somente na Ayla. O WhatsApp será preservado porque não foi confirmado um grupo principal com apenas o seu número.'};
    }
    async removePersonalConversation(conversationId) {
        return this.control(async()=>{
            if(this.phase!=='ready'||!this.client||this.stopping)throw Object.assign(new Error('WhatsApp indisponível'),{status:503});
            if(!await this.repository.accountEnabled())throw Object.assign(new Error('Conta inativa'),{status:403});
            const contact=await this.conversationContact(conversationId),key='front-delete:'+conversationId;
            const previous=await this.repository.pool.query('select status from whatsapp_messages where instance_id=$1 and message_id=$2',[this.config.instanceId,key]);
            if(previous.rows[0]?.status==='sent')return {deleted:true};
            if(!contact.enabled||contact.mention_only||!contact.is_group)throw Object.assign(new Error('Conversa não é grupo principal'),{status:409});
            const client=this.client;
            const info=await client.inspectPersonalGroup(contact.chat_id);
            const continuing=previous.rows.length>0;
            if(!info.eligible&&!(continuing&&info.alreadyLeft))throw Object.assign(new Error('Grupo não contém somente o próprio número'),{status:409});
            if(!continuing)await this.repository.claim(key,contact.chat_id);
            this.cancelBuffered(contact.chat_id);
            if(!info.alreadyLeft)await client.inspectPersonalGroup(contact.chat_id,true);
            await this.repository.finish(key,'left');
            const deleted=await client.deletePersonalGroupChat(contact.chat_id);
            if(deleted!==true && deleted!==false)throw Object.assign(new Error('Exclusão local não confirmada'),{status:503});
            await this.repository.finish(key,'sent');
            await this.repository.pool.query('update whatsapp_chats set enabled=false,updated_at=now() where id=$1 and instance_id=$2',[contact.id,this.config.instanceId]);
            const saved=await this.repository.instance();
            if(saved.personal_group_chat_id===contact.chat_id){await this.repository.savePersonalGroup(null);this.personalGroupId=null;}
            return {deleted:true};
        });
    }
    async sendCalendarReminder(id) {
        const client=this.client;
        if(!client || this.phase!=='ready' || this.stopping)
            return {status:'FRONT_ONLY'};
        if(!await this.repository.accountEnabled())throw Object.assign(new Error('Conta inativa'),{status:403});
        const result=await this.repository.pool.query(`select r.whatsapp_status,e.title,e.location,e.starts_at,e.time_zone
            from calendar_reminders r join calendar_events e on e.id=r.event_id
            where r.id=$1 and e.user_id=$2 and e.version=r.event_version and e.status='ACTIVE'
            and not r.cancelled and r.notified_at is not null and r.due_at<=now() and ((r.reminder_type='AT_TIME' and r.due_at>now()-interval '5 minutes') or (r.reminder_type='BEFORE_START' and (e.starts_at>now() or (r.minutes_before=0 and e.starts_at>now()-interval '5 minutes'))))`,[id,this.config.ownerId]);
        const reminder=result.rows[0];
        if(!reminder)return {status:'SKIPPED'};
        if(reminder.whatsapp_status!=='PENDING')return {status:reminder.whatsapp_status};
        let primary=(await this.repository.list()).find(chat=>chat.enabled && !chat.mentionOnly);
        if(!primary)primary=(await this.createPersonalChat()).chat;
        if(!primary?.enabled || primary.mentionOnly)throw Object.assign(new Error('Conversa principal indisponível'),{status:409});
        if(this.client!==client || this.phase!=='ready' || this.stopping)return {status:'FRONT_ONLY'};
        const claimed=await this.repository.pool.query(`update calendar_reminders r set whatsapp_status='SENDING',whatsapp_attempt_at=now()
            from calendar_events e where r.id=$1 and r.event_id=e.id and e.user_id=$2 and e.version=r.event_version
            and e.status='ACTIVE' and not r.cancelled and r.whatsapp_status='PENDING' returning r.id`,[id,this.config.ownerId]);
        if(!claimed.rowCount)return {status:'ALREADY_CLAIMED'};
        try {
            if(this.client!==client || this.phase!=='ready')throw new Error('Connection changed');
            const date=new Intl.DateTimeFormat('pt-BR',{dateStyle:'short',timeStyle:'short',timeZone:reminder.time_zone}).format(new Date(reminder.starts_at));
            await this.sendText(client,primary.chatId,formatReply(`Ei! Passando para lembrar: ${reminder.title}\n${date}${reminder.location?'\nLocal: '+reminder.location:''}`));
            await this.repository.pool.query("update calendar_reminders set whatsapp_status='SENT' where id=$1",[id]);
            return {status:'SENT'};
        } catch(error) {
            await this.repository.pool.query("update calendar_reminders set whatsapp_status='UNCERTAIN' where id=$1",[id]).catch(()=>{});
            throw Object.assign(new Error('Entrega do lembrete não confirmada'),{status:503});
        }
    }
    async sendPasswordRecovery({userId,registeredNumber,password,requestId}) {
        const client=this.client;
        if(!client || this.phase!=='ready' || this.stopping)throw Object.assign(new Error('WhatsApp indisponível'),{status:503});
        const instance=await this.repository.instance();
        if(userId!==this.config.ownerId || instance.owner_user_id!==userId)throw Object.assign(new Error('Destinatário não autorizado'),{status:403});
        const destination=wid(client.info?.wid);
        if(!destination || !/@(?:c\.us|lid)$/.test(destination) || String(client.info?.wid?.user)!==String(instance.connected_number) || String(instance.connected_number)!==registeredNumber)
            throw Object.assign(new Error('Número cadastrado indisponível'),{status:503});
        const key='recovery:'+requestId;
        if(!await this.repository.claim(key,destination))return {sent:false,duplicate:true};
        const text=`${RECOVERY_PREFIX}\n\nSua nova senha: ${password}\n\nEntre com ela em até 15 minutos para substituir a senha anterior. Se não solicitou, ignore: sua senha atual continua funcionando. Você pode escolher outra senha depois de entrar.`;
        const echo=destination+':'+createHash('sha256').update(text.trim()).digest('hex');
        this.outgoingTextHashes.set(echo,Date.now()+120000);
        try {
            if(this.client!==client || this.phase!=='ready' || this.stopping)throw new Error('Connection changed');
            const sent=await client.sendMessage(destination,text,{sendSeen:false});
            const id=sent&&messageKey(sent);
            if(id)await this.repository.markSensitive(id,destination);
            await this.repository.finish(key,'sent');
            return {sent:true};
        } catch(error) {
            await this.repository.finish(key,'failed').catch(()=>{});
            throw Object.assign(new Error('Não foi possível confirmar a entrega da recuperação.'),{status:503});
        }
    }
    async sendText(client,chatId,text) {
        const key=chatId+':'+createHash('sha256').update(text.trim()).digest('hex');
        this.outgoingTextHashes.set(key,Date.now()+120000);
        if(this.outgoingTextHashes.size>500) this.outgoingTextHashes.delete(this.outgoingTextHashes.keys().next().value);
        const sent=await client.sendMessage(chatId,text,{sendSeen:false});
        const sentId=sent && messageKey(sent);
        if(sentId && await this.repository.claim(sentId,chatId)) await this.repository.finish(sentId,'sent',text);
    }
    async send(chatId, text, messageId) {
        const ids=Array.isArray(messageId)?messageId:[messageId];
        const audio=text && typeof text==='object'?text.audio:null;
        const formattedReply=formatReply(text && typeof text === 'object' ? text.text : text);
        const reply=formattedReply.slice(HEADER.length).trim();
        const sendingClient=this.client;
        await Promise.all(ids.map(id=>this.repository.finish(id,'sending',reply)));
        await this.sendText(sendingClient,chatId,audio?HEADER:formattedReply);
        if(audio && this.client===sendingClient && this.phase==='ready') {
            try {
                const contact=await this.repository.find(chatId,null);
                if(!contact?.enabled) {
                    await Promise.all(ids.map(id=>this.repository.finish(id,'cancelled')));return;
                }
                const hash=createHash('sha256').update(Buffer.from(audio.data,'base64')).digest('base64');
                this.outgoingVoiceHashes.add(hash);
                if(this.outgoingVoiceHashes.size>500) this.outgoingVoiceHashes.delete(this.outgoingVoiceHashes.values().next().value);
                const {MessageMedia}=require('whatsapp-web.js');
                const voice=sendingClient.sendVoiceMedia?await sendingClient.sendVoiceMedia(chatId,audio)
                    :await sendingClient.sendMessage(chatId,new MessageMedia(audio.mimeType,audio.data,audio.mimeType==='audio/ogg'?'ayla.ogg':'ayla.mp3'),{
                        sendSeen:false,sendAudioAsVoice:audio.mimeType==='audio/ogg',
                    });
                const voiceId=voice && messageKey(voice);
                if(voiceId && await this.repository.claim(voiceId,chatId)) await this.repository.finish(voiceId,'sent',reply);
            } catch(error) {
                const detail=String(error.message||error.name).replace(/https?:\/\/\S+/g,'[url]').slice(0,600);
                this.log.error('[whatsapp] voz indisponível; entregando resposta em texto:',JSON.stringify({type:error.name,detail}));
                if(this.client===sendingClient && this.phase==='ready' && (await this.repository.find(chatId,null))?.enabled)
                    await this.sendText(sendingClient,chatId,formattedReply);
                await Promise.all(ids.map(id=>this.repository.finish(id,'sent',reply)));
                return;
            }
            if(text.textSupplement && this.client===sendingClient && this.phase==='ready'
                    && (await this.repository.find(chatId,null))?.enabled)
                await this.sendText(sendingClient,chatId,formatReply(text.textSupplement));
        }
        await Promise.all(ids.map(id=>this.repository.finish(id,'sent',reply)));
    }
}
module.exports = { WhatsappManager, defaultClient };

