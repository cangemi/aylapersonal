const {test}=require('node:test');
const assert=require('node:assert/strict');
const {injectAfterNavigation}=require('../navigationRecovery');
test('retries only a destroyed navigation context while the page remains open',async()=>{
    let calls=0;
    assert.equal(await injectAfterNavigation(async()=>{if(++calls<3)throw new Error('Execution context was destroyed.');return 'ready';},()=>true,async()=>{}),'ready');
    assert.equal(calls,3);
    await assert.rejects(injectAfterNavigation(async()=>{throw new Error('permission denied');},()=>true,async()=>{}),/permission denied/);
    await assert.rejects(injectAfterNavigation(async()=>{throw new Error('Execution context was destroyed.');},()=>false,async()=>{}),/context was destroyed/);
});
