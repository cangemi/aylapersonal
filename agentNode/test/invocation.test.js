const test=require('node:test');
const assert=require('node:assert/strict');
const {invocation,readContextFromPage,contextView,normalizeCommand,COMMAND_TOKEN,RECOVERY_PREFIX}=require('../invocation');

test('invocation accepts an exact alias, defaults to ten, supports explicit counts and bounds the context',()=>{
    for(const [text,count] of [['Ayla0101 anote esses itens',10],['Leia as últimas 25 mensagens Ayla0101',25],
        ['Ayla0101 leia 3 mensagens',3],['Ayla0101 sem contexto',0],['Ayla0101 leia mais',20],['Ayla0101 leia menos',5],['Ayla0101 leia 999 mensagens',100]])
        assert.equal(invocation(text,'ayla0101').limit,count);
    assert.equal(invocation('xAyla0101x','ayla0101'),null);
    assert.equal(invocation('Oi!','ayla0101'),null);
    assert.equal(COMMAND_TOKEN,'ayla0101');
    assert.equal(normalizeCommand('  CONFIGURAR   AyLa0101  '),'configurar ayla0101');
    assert.equal(invocation('Ayla0101 teste','  AyLa0101 ').text,'teste');
    const result={messages:Array.from({length:100},(_,i)=>({id:{_serialized:String(i)},body:'a'.repeat(6000),fromMe:false})),quoted:{body:'itens',fromMe:false}};
    const view=contextView(result,100);
    assert.ok(view.messages.reduce((n,m)=>n+m.text.length,0)<=18000);
    assert.equal(view.quoted.owner,false);
    assert.equal(contextView(result,0).messages.length,0);
});

test('explicit native context fetch is scoped, ordered, bounded, prioritizes quote and suppresses historical live events',async()=>{
    const previous=global.window,chatId='123456789@g.us';
    const make=(id,t,body)=>({id:{$1:id,remote:{_serialized:chatId},fromMe:false},t,body,type:'chat'});
    const old=Array.from({length:15},(_,i)=>make(`m${i}`,i+1,`texto ${i}`));
    old[13].body=RECOVERY_PREFIX+'\nCredencial que não pode chegar à IA';
    const trigger=make('trigger',30,'Ayla0101 analise');trigger.id.fromMe=true;trigger.quotedStanzaID='quote';
    const quote=make('quote',1,'Arroz e leite');let quoted=quote;
    const future=make('future',30,'não é contexto: chegou após a chamada no mesmo segundo');
    let models=[...old.slice(-4),trigger,future],loads=0;
    const byId=new Map([...old,trigger,future,quote].map(m=>[m.id.$1,m]));
    const chat={msgs:{getModelsArray:()=>models}};
    global.window={WWebJS:{getChat:async()=>chat},require:name=>{
        if(name==='WAWebCollections')return {Msg:{get:id=>byId.get(id)}};
        if(name==='WAWebChatLoadMessages')return {loadEarlierMsgs:async()=>{
            loads++;assert.equal(window.aylaContextReadCutoffs.get(chatId),30);
            models=[...old,trigger,future];return old;
        }};
        if(name==='WAWebQuotedMsgModelUtils')return {getQuotedMsgObj:()=>quoted};
        throw new Error(name);
    }};
    try {
        const page={evaluate:(fn,...args)=>fn(...args)};
        const result=await readContextFromPage(page,chatId,'trigger','trigger',10,['trigger'],true);
        assert.equal(loads,1);assert.equal(result.messages.length,10);
        assert.equal(result.messages[0].body,'texto 4');
        assert.ok(!JSON.stringify(result).includes('Credencial'));
        assert.ok(!result.messages.some(m=>m.id._serialized==='future'));
        assert.equal(result.quoted.body,'Arroz e leite');
        assert.equal(window.aylaContextReadCutoffs.size,0);
        quoted=old[13];
        assert.equal((await readContextFromPage(page,chatId,'trigger','trigger',10,['trigger'],true)).quoted,null);
        quoted.body='Conteúdo editado, ainda sensível pelo ID';
        assert.equal((await readContextFromPage(page,chatId,'trigger','trigger',10,['trigger','m13'],true)).quoted,null);
        quoted=quote;
        loads=0;
        assert.equal((await readContextFromPage(page,chatId,'trigger','trigger',0,['trigger'],true)).messages.length,0);
        assert.equal(loads,0);
    }finally{global.window=previous;}
});
