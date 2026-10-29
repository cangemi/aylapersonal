const { messageKey } = require('./messageIdentity');
const COMMAND_TOKEN = 'ayla0101';
const RECOVERY_PREFIX = '-------Ayla--------\n\n🔐 Recuperação de acesso da Ayla';
function normalizeCommand(text) {return String(text||'').normalize('NFKC').trim().replace(/\s+/g,' ').toLowerCase();}

function invocation(text, token = COMMAND_TOKEN) {
    text=String(text||'').normalize('NFKC');
    token=normalizeCommand(token);
    const escaped = token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const mention = new RegExp(`(^|[^\\p{L}\\p{N}_])@?${escaped}(?=$|[^\\p{L}\\p{N}_])`, 'iu');
    if (!mention.test(text)) return null;
    const simplified = text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
    const count = simplified.match(/\b(\d{1,5})\s+(?:ultimas?\s+)?mensagens\b/);
    let limit = count ? Number(count[1]) : /\b(sem (?:historico|contexto)|so (?:essa|esta|a mensagem citada))\b/.test(simplified) ? 0
        : /\b(?:leia|ler|le)\s+mais\b/.test(simplified) ? 20 : /\b(?:leia|ler|le)\s+menos\b/.test(simplified) ? 5 : 10;
    const warning=limit>100?'A leitura foi limitada a 100 mensagens.':'';
    return {limit:Math.min(limit,100),warning,text:text.replace(mention, '$1').trim() || 'Responda considerando o contexto selecionado.'};
}

async function readContextFromPage(page, chatId, anchorId, triggerId, limit, excludedIds, expectedQuote=false) {
    return page.evaluate(async (chatId, anchorId, triggerId, limit, excludedIds, expectedQuote, sensitivePrefix) => {
        const {Msg} = window.require('WAWebCollections');
        const anchor = Msg.get(anchorId), trigger = Msg.get(triggerId);
        if (!anchor || !trigger) throw new Error('Invocation message unavailable');
        const key = m => m?.id?._serialized || m?.id?.$1;
        const sameChat = m => {
            const remote=m?.id?.remote;
            return (typeof remote==='string'?remote:remote?._serialized||remote?.$1||`${remote?.user}@${remote?.server}`)===chatId;
        };
        const serialize = m => ({
            id:{_serialized:key(m)},body:String(m.body||m.caption||'').slice(0,6000),
            fromMe:Boolean(m.id.fromMe),from:chatId,to:chatId,timestamp:Number(m.t),type:m.type,
            author:m.id.fromMe?'Você':String(m.notifyName||'Outro participante').slice(0,80),
            hasMedia:Boolean(m.directPath),rawData:{mimetype:m.mimetype,size:m.size,duration:m.duration,filename:m.filename},
        });
        const chat=await window.WWebJS.getChat(chatId,{getAsModel:false});
        const excluded=new Set(excludedIds);
        const eligible=m=>sameChat(m)&&key(m)&&!excluded.has(key(m))&&!m.isNotification
            &&!String(m.body||m.caption||'').startsWith(sensitivePrefix)
            &&!['reaction','revoked','ciphertext','e2e_notification'].includes(m.type)&&Number(m.t)<=Number(anchor.t);
        window.aylaContextReadCutoffs ||= new Map();
        window.aylaContextReadCutoffs.set(chatId,Number(anchor.t));
        try {
            const collect=()=>{
                const all=chat.msgs.getModelsArray(),index=all.findIndex(m=>key(m)===anchorId);
                return (index<0?all:all.slice(0,index+1)).filter(eligible);
            };
            let messages=collect();
            // Bounded explicit history read; never feed loaded records back into live handling.
            for(let attempt=0;limit>0&&messages.length<limit&&attempt<5;attempt++) {
                const loaded=await window.require('WAWebChatLoadMessages').loadEarlierMsgs({chat});
                if(!loaded?.length) break;
                messages=collect();
            }
            const ids=new Set();
            messages=messages.filter(m=>{const id=key(m);if(ids.has(id))return false;ids.add(id);return true;});
            messages.sort((a,b)=>Number(a.t)-Number(b.t));
            let quoted=null,sensitiveQuote=false;
            if(expectedQuote || trigger.quotedMsg || trigger.quotedStanzaID || trigger.quotedMsgKey) {
                const model=window.require('WAWebQuotedMsgModelUtils').getQuotedMsgObj(trigger);
                sensitiveQuote=Boolean(model && (excluded.has(key(model)) || String(model.body||model.caption||'').startsWith(sensitivePrefix)));
                if(model && sameChat(model) && key(model) && !excluded.has(key(model)) && !String(model.body||model.caption||'').startsWith(sensitivePrefix)) {
                    if(!Msg.get(key(model))) await Msg.getMessagesById([key(model)]);
                    quoted=serialize(Msg.get(key(model))||model);
                }
            }
            return {messages:limit?messages.slice(-limit).map(serialize):[],quoted,sensitiveQuote,
                warning:limit>messages.length?'O WhatsApp disponibilizou menos mensagens que o solicitado.':''};
        } finally {window.aylaContextReadCutoffs.delete(chatId);}
    },chatId,anchorId,triggerId,limit,excludedIds,expectedQuote,RECOVERY_PREFIX);
}

function contextView(result, limit) {
    const view = (m, quoted=false) => ({id:messageKey(m)||'',author:m.author|| (m.fromMe?'Você':'Outro participante'),
        owner:m.fromMe===true,text:String(m.body||'').slice(0,quoted?6000:2000),type:m.type||'chat',timestamp:m.timestamp||0});
    let budget=18000;
    const messages=(limit?(result.messages||[]).slice(-limit):[]).reverse().map(m=>{
        const item=view(m);item.text=item.text.slice(0,Math.max(0,budget));budget-=item.text.length;return item;
    }).reverse();
    return {messages,quoted:result.quoted?view(result.quoted,true):null,requestedCount:limit,warning:result.warning||''};
}

module.exports={invocation,readContextFromPage,contextView,COMMAND_TOKEN,normalizeCommand,RECOVERY_PREFIX};
