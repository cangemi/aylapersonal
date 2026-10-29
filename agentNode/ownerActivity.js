// Read only owner presence exposed by WhatsApp. Other participants cannot hold a batch.
function ownerTypingInPage(chatId) {
    const collections=window.require('WAWebCollections'),me=window.require('WAWebUserPrefsMeUser');
    const presence=collections.Chat.get(chatId)?.presence||collections.Presence?.get(chatId);
    if(!presence)return false;
    const isMe=id=>{try{return Boolean(id&&me.isMeAccount(id.id||id.wid||id));}catch{return false;}};
    const values=value=>Array.isArray(value)?value:value instanceof Set?[...value]:value?.getModelsArray?.()||[];
    const users=[...values(presence.typingUserIds),...values(presence.recordingUserIds)];
    const state=presence.chatstate;
    return users.some(isMe)||(isMe(presence.id)&&['composing','typing','recording'].includes(typeof state==='string'?state:state?.type));
}
async function installOwnerActivity(client) {
    const page=client.pupPage;
    if(!await page.evaluate(()=>typeof window.onAylaOwnerActivity==='function'))
        await page.exposeFunction('onAylaOwnerActivity',event=>client.emit('ayla_owner_activity',event));
    await page.evaluate(()=>{
        const {Presence}=window.require('WAWebCollections'),me=window.require('WAWebUserPrefsMeUser');
        if(!Presence?.on || window.aylaOwnerActivity?.collection===Presence)return;
        const old=window.aylaOwnerActivity;if(old)old.collection.off('change',old.handler);
        const last=new Map();
        const handler=model=>{
            try {
                const chatId=model.id?._serialized||model.id?.$1||String(model.id||'');if(!/@(?:g\.us|c\.us|lid)$/.test(chatId))return;
                const values=value=>Array.isArray(value)?value:value instanceof Set?[...value]:value?.getModelsArray?.()||[];
                const isMe=id=>{try{return Boolean(id&&me.isMeAccount(id.id||id.wid||id));}catch{return false;}};
                const state=model.chatstate;
                const active=[...values(model.typingUserIds),...values(model.recordingUserIds)].some(isMe)
                    ||(isMe(model.id)&&['composing','typing','recording'].includes(typeof state==='string'?state:state?.type));
                const previous=last.get(chatId);
                if(previous===active)return;last.set(chatId,active);
                if(last.size>500)last.delete(last.keys().next().value);
                if(!active && previous!==true)return;
                Promise.resolve(window.onAylaOwnerActivity({chatId,active})).catch(()=>{});
            }catch{/* Do not block messaging if WhatsApp changes presence models. */}
        };
        Presence.on('change',handler);window.aylaOwnerActivity={collection:Presence,handler};
    });
}
module.exports={ownerTypingInPage,installOwnerActivity};
