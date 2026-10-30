function chatIdOf(value) {
    let id = String(value || '').trim();
    if (/^\+?[1-9]\d{7,14}$/.test(id)) id = id.replace(/^\+/, '') + '@c.us';
    id = id.replace(/@s\.whatsapp\.net$/, '@c.us');
    if (!/^[0-9-]{5,40}@(c\.us|g\.us|lid)$/.test(id)) throw Object.assign(new Error('Informe telefone com país/DDD ou um chatId WhatsApp válido.'), { status: 400 });
    return id;
}
function view(row) {
    return row && { id: row.id, chatId: row.chat_id, phone: row.phone, name: row.display_name,
        isGroup: row.is_group, enabled: row.enabled, mentionOnly: row.mention_only, conversationId: row.conversation_id, historyResetAt: row.history_reset_at || null,
        createdAt: row.created_at, updatedAt: row.updated_at };
}
class WhatsappRepository {
    constructor(pool, config) { this.pool = pool; this.config = config; }
    async init() {
        // Flyway owns schema creation. Do not create a second independent migration history.
        await this.pool.query('select 1 from whatsapp_instances limit 0');
        await this.pool.query(`insert into chat_users(user_id,hermes_profile,created_at) values ($1,$2,CURRENT_TIMESTAMP) on conflict(user_id) do nothing`, [this.config.ownerId, this.config.defaultProfile === undefined ? 'default' : this.config.defaultProfile]);
        await this.pool.query('insert into whatsapp_instances(id,owner_user_id,connection_role) values ($1,$2,$3) on conflict(id) do nothing', [this.config.instanceId, this.config.ownerId, this.config.connectionRole || 'user']);
        const state = await this.instance();
        if (state.owner_user_id !== this.config.ownerId) throw new Error('A instância WhatsApp pertence a outro proprietário.');
        if (state.connection_role !== (this.config.connectionRole || 'user')) throw new Error('A instância WhatsApp pertence a outra função.');
        return state;
    }
    async instance() {
        const result = await this.pool.query(`select i.*,s.updated_at as session_saved_at from whatsapp_instances i
            left join whatsapp_sessions s on s.instance_id=i.id where i.id=$1`, [this.config.instanceId]);
        return result.rows[0];
    }
    async accountEnabled() {
        const result=await this.pool.query('select exists(select 1 from auth_accounts where user_id=$1 and enabled) as enabled',[this.config.ownerId]);
        return result.rows[0].enabled===true;
    }
    async state(state, desired, info = {}) {
        await this.pool.query(`update whatsapp_instances set state=$2,desired_connected=coalesce($3,desired_connected),
            connected_number=$4,connected_name=$5,last_error=$6,updated_at=CURRENT_TIMESTAMP where id=$1`,
            [this.config.instanceId, state, desired == null ? null : desired, info.number || null, info.name || null, info.error || null]);
    }
    async savePersonalGroup(chatId) {
        await this.pool.query('update whatsapp_instances set personal_group_chat_id=$2,updated_at=CURRENT_TIMESTAMP where id=$1',
            [this.config.instanceId,chatId]);
    }
    async configure(mode) {
        await this.pool.query('update whatsapp_instances set connection_mode=$2,updated_at=CURRENT_TIMESTAMP where id=$1', [this.config.instanceId, mode]);
    }
    async list() {
        const result = await this.pool.query('select * from whatsapp_chats where instance_id=$1 order by created_at,id', [this.config.instanceId]);
        return result.rows.map(view);
    }
    async get(id, db=this.pool) {
        const result = await db.query('select * from whatsapp_chats where instance_id=$1 and id=$2', [this.config.instanceId, id]);
        return view(result.rows[0]);
    }
    async find(chatId, phone, db=this.pool) {
        const result = await db.query(`select * from whatsapp_chats where instance_id=$1 and (chat_id=$2 or (phone=$3 and $3 is not null)) limit 1`,
            [this.config.instanceId, chatId, phone || null]);
        return view(result.rows[0]);
    }
    fields(input, current) {
        const id = chatIdOf(input.chatId || input.phone || current?.chatId);
        const isGroup = id.endsWith('@g.us');
        let phone = isGroup ? null : !id.endsWith('@lid') ? id.split('@')[0] : input.phone || current?.phone || null;
        if (phone) { phone = String(phone).replace(/^\+/, ''); if (!/^[1-9]\d{7,14}$/.test(phone)) throw Object.assign(new Error('Telefone inválido; informe país e DDD.'), { status: 400 }); }
        const name = String(input.name ?? current?.name ?? phone ?? id).trim();
        if (!name || name.length > 160 || (input.enabled !== undefined && typeof input.enabled !== 'boolean'))
            throw Object.assign(new Error('Nome ou habilitação inválidos.'), { status: 400 });
        if (input.mentionOnly !== undefined && typeof input.mentionOnly !== 'boolean') throw Object.assign(new Error('Modo de chamada inválido.'), {status:400});
        return { id, isGroup, phone, name, enabled: input.enabled ?? current?.enabled ?? true, mentionOnly: input.mentionOnly ?? current?.mentionOnly ?? true };
    }
    async change(work) {
        const db=await this.pool.connect();
        try {
            await db.query('begin');
            await db.query('select id from whatsapp_instances where id=$1 for update',[this.config.instanceId]);
            const result=await work(db);
            await db.query('commit');
            return result;
        } catch(error) {await db.query('rollback');throw error;} finally {db.release();}
    }
    async demoteOtherPrimary(db,chatId,value) {
        if(!value.enabled || value.mentionOnly)return;
        await db.query(`update whatsapp_chats set mention_only=true,conversation_id=null,updated_at=CURRENT_TIMESTAMP
            where instance_id=$1 and chat_id<>$2 and enabled and not mention_only`,[this.config.instanceId,chatId]);
    }
    async authorize(input, actor) {
        return this.change(async db=>{
            const value=this.fields(input);
            const existing=await this.find(value.id,value.phone,db);
            if(existing)return this.updateIn(db,existing.id,{name:value.name,enabled:true,mentionOnly:value.mentionOnly});
            await this.demoteOtherPrimary(db,value.id,value);
            const result=await db.query(`insert into whatsapp_chats(instance_id,chat_id,phone,display_name,is_group,enabled,authorized_by,mention_only)
                values ($1,$2,$3,$4,$5,$6,$7,$8) returning *`,
                [this.config.instanceId,value.id,value.phone,value.name,value.isGroup,value.enabled,actor||'api',value.mentionOnly]);
            return view(result.rows[0]);
        });
    }
    async update(id, input) {
        return this.change(db=>this.updateIn(db,id,input));
    }
    async updateIn(db,id,input) {
        const current = await this.get(id,db);
        if (!current) throw Object.assign(new Error('Conversa não encontrada.'), { status: 404 });
        const value = this.fields(input, current);
        await this.demoteOtherPrimary(db,value.id,value);
        const result = await db.query(`update whatsapp_chats set chat_id=$3::varchar,phone=$4::varchar,display_name=$5,is_group=$6,enabled=$7,mention_only=$8,
            conversation_id=case when chat_id<>$3::varchar or phone is distinct from $4::varchar or mention_only<>$8 then null else conversation_id end,
            updated_at=CURRENT_TIMESTAMP where instance_id=$1 and id=$2 returning *`,
            [this.config.instanceId, id, value.id, value.phone, value.name, value.isGroup, value.enabled, value.mentionOnly]);
        return view(result.rows[0]);
    }
    async remove(id) {
        const result = await this.pool.query('delete from whatsapp_chats where instance_id=$1 and id=$2', [this.config.instanceId, id]);
        if (!result.rowCount) throw Object.assign(new Error('Conversa não encontrada.'), { status: 404 });
    }
    async claim(messageId, chatId) {
        const result = await this.pool.query(`insert into whatsapp_messages(instance_id,message_id,chat_id) values ($1,$2,$3)
            on conflict do nothing returning message_id`, [this.config.instanceId, messageId, chatId]);
        return result.rowCount > 0;
    }
    async finish(messageId, status, reply) {
        await this.pool.query(`update whatsapp_messages set status=$3,response_text=case when sensitive then null else coalesce($4,response_text) end,updated_at=CURRENT_TIMESTAMP where instance_id=$1 and message_id=$2`,
            [this.config.instanceId, messageId, status, reply || null]);
    }
    async markSensitive(messageId,chatId) {
        await this.pool.query(`insert into whatsapp_messages(instance_id,message_id,chat_id,status,sensitive) values ($1,$2,$3,'sent',true)
            on conflict(instance_id,message_id) do update set sensitive=true,response_text=null,status='sent',updated_at=CURRENT_TIMESTAMP`,[this.config.instanceId,messageId,chatId]);
    }
    async sensitiveMessageIds(chatId) {
        const result=await this.pool.query('select message_id from whatsapp_messages where instance_id=$1 and chat_id=$2 and sensitive',[this.config.instanceId,chatId]);
        return result.rows.map(row=>row.message_id);
    }
}
module.exports = { WhatsappRepository, chatIdOf };


