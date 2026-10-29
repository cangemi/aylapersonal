const test=require('node:test'),assert=require('node:assert/strict');
const {reportWhatsappFailure}=require('../monitoring');
test('WhatsApp monitoring sends only a fixed stage code and cannot break message handling',async()=>{
    const config={javaUrl:'http://java:8080',apiKey:'k'.repeat(36)};
    let sent;
    assert.equal(await reportWhatsappFailure(config,'baixar áudio',async(url,options)=>{sent=JSON.parse(options.body);assert.equal(url,'http://java:8080/internal/monitoring/whatsapp');return{ok:true};}),true);
    assert.deepEqual(sent,{stage:'AUDIO_DOWNLOAD'});
    assert.equal(await reportWhatsappFailure(config,'baixar imagem',async()=>{throw new Error('offline');}),false);
});
