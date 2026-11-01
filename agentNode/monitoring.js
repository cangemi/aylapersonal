function failureStage(stage) {
    if(stage==='baixar áudio')return 'AUDIO_DOWNLOAD';
    if(stage==='baixar imagem')return 'IMAGE_DOWNLOAD';
    if(stage==='baixar documento')return 'DOCUMENT_DOWNLOAD';
    if(stage.startsWith('enviar'))return 'DELIVERY';
    if(stage==='consultar Ayla')return 'AGENT';
    if(stage.includes('autorização'))return 'AUTHORIZATION';
    if(stage.includes('contexto'))return 'CONTEXT';
    return 'OTHER';
}
async function reportWhatsappFailure(config,stage,requestKey,send=fetch) {
    if(!config.javaUrl||!config.apiKey||config.apiKey.length<32)return false;
    // Only the fixed stage code and the opaque request key travel: never message text or exception payload.
    const body={stage:failureStage(stage)};
    if(typeof requestKey==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(requestKey))body.requestKey=requestKey;
    try {
        const response=await send(config.javaUrl+'/internal/monitoring/whatsapp',{
            method:'POST',headers:{Authorization:'Bearer '+config.apiKey,'Content-Type':'application/json'},
            body:JSON.stringify(body),signal:AbortSignal.timeout(2000),
        });
        return response.ok;
    }catch{return false;}
}
module.exports={reportWhatsappFailure,failureStage};
