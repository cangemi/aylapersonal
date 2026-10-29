const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const {sendVoiceFromPage}=require('../voiceMedia');
test('native voice sends without caption/seen and avoids full model serialization after delivery',async()=>{
 let calls=0;const window={WWebJS:{async getChat(id,options){assert.equal(id,'group');assert.equal(options.getAsModel,false);return {};},
 async sendMessage(chat,text,options){calls++;assert.equal(options.caption,undefined);assert.equal(options.sendAudioAsVoice,true);assert.equal(options.media.mimetype,'audio/ogg');return {id:{$1:'sent-voice'}};},
 getMessageModel(){throw Error('Must not serialize entire model');}}};
 const page={async evaluate(fn,...args){return vm.runInNewContext('('+fn.toString()+')(...args)',{window,args});}};
 assert.deepEqual(await sendVoiceFromPage(page,'group',{mimeType:'audio/ogg',data:'T2dnUw=='}),{id:{_serialized:'sent-voice'}});assert.equal(calls,1);
 window.WWebJS.sendMessage=async()=>{throw {name:'MediaError',message:'Invalid caption'};};
 await assert.rejects(()=>sendVoiceFromPage(page,'group',{data:'T2dnUw=='}),/enviar voz: MediaError: Invalid caption/);
});

test('missing SDK return is resolved by matching the new outgoing voice, never an older message',async()=>{
 const hash=require('node:crypto').createHash('sha256').update(Buffer.from('OggS')).digest('base64');
 const messages=[{id:{$1:'true_group_old',fromMe:true},filehash:hash}];
 const window={require(){return {Msg:{getModelsArray:()=>messages}};},WWebJS:{async getChat(){return {};},async sendMessage(){
     messages.push({id:{$1:'true_group_new',fromMe:true},filehash:hash});return undefined;
 }}};
 const page={async evaluate(fn,...args){return vm.runInNewContext('('+fn.toString()+')(...args)',{window,args,Set});}};
 assert.deepEqual(await sendVoiceFromPage(page,'group',{mimeType:'audio/ogg',data:'T2dnUw=='}),{id:{_serialized:'true_group_new'}});
});
