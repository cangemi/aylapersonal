const {createHash}=require('node:crypto');
// Keep browser-side errors legible and return only the sent ID: serializing a full
// WhatsApp model is unnecessary and can fail after the message was already sent.
async function sendVoiceFromPage(page,chatId,audio) {
    const hash=createHash("sha256").update(Buffer.from(audio.data,"base64")).digest("base64");
    const result=await page.evaluate(async (id,audio,hash)=>{
        let stage='abrir conversa';
        try {
            const chat=await window.WWebJS.getChat(id,{getAsModel:false});
            if(!chat) throw new Error('Conversa indisponível');
            const collection=window.require?.('WAWebCollections')?.Msg;
            const keyOf=m=>m?.id?._serialized||m?.id?.$1;
            const before=new Set((collection?.getModelsArray()||[]).map(keyOf));
            stage='enviar voz';
            const msg=await window.WWebJS.sendMessage(chat,'',{
                media:{mimetype:audio.mimeType,data:audio.data,filename:audio.mimeType==='audio/ogg'?'ayla.ogg':'ayla.mp3'},
                sendAudioAsVoice:audio.mimeType==='audio/ogg',linkPreview:false,parseVCards:false,
                // A PTT cannot carry a caption. The header is sent in the accompanying text.
            });
            // Current WhatsApp uses id.$1; SDK's final lookup still uses _serialized
            // and may return undefined AFTER sending. Find only this newly created voice.
            const key=keyOf(msg)||(collection?.getModelsArray()||[]).filter(m=>{
                const candidate=keyOf(m);
                return m.id?.fromMe && m.filehash===hash && candidate && !before.has(candidate)
                    && candidate.includes('_'+id+'_');
            }).map(keyOf).at(-1);
            if(!key) throw new Error('Envio sem identificador de confirmação');
            return {id:key};
        }catch(error){return {error:{stage,name:String(error?.name||'VoiceError'),message:String(error?.message||error).slice(0,400),stack:String(error?.stack||'').replace(/https?:\/\/\S+/g,'[url]').slice(0,1800)}};}
    },chatId,audio,hash);
    if(result?.error) throw Object.assign(new Error(`${result.error.stage}: ${result.error.name}: ${result.error.message}`),{browserStack:result.error.stack});
    return {id:{_serialized:result.id}};
}
module.exports={sendVoiceFromPage};


