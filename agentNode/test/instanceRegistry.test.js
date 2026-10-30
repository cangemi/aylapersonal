const test = require('node:test');
const assert = require('node:assert/strict');
const {InstanceRegistry} = require('../instanceRegistry');

test('each user retains the original instance and gets a separate bounded assistant identity, restored once with its mode', async () => {
    const starts = [], configs = [], saved = new Map();
    const registry = new InstanceRegistry({config:{ownerId:'owner',instanceId:'original'},pool:{async query(){return {rows:[]};}},
        createRepository(pool, config) { configs.push(config); return {config}; },
        createStore(pool, config) { return {id:config.instanceId}; },
        createManager({config,store}) {
            return {async start() {
                starts.push(config.instanceId);
                assert.equal(store.id,config.instanceId);
                if (config.connectionRole === 'user' && saved.get(config.ownerId) === 'dedicated') await this.getAssistant();
            }, async shutdown() {}};
        },
    });
    saved.set('owner','dedicated');
    const owner = await registry.get('owner');
    const peer = await registry.get('owner','ayla');
    assert.equal(owner.repository.config.instanceId,'original');
    assert.notEqual(peer.repository.config.instanceId,'original');
    assert.equal(peer.repository.config.ownerId,'owner');
    assert.equal(peer.repository.config.connectionRole,'ayla');
    assert.equal(await owner.manager.getAssistant(),peer.manager);
    assert.equal(starts.length,2);
    const other = await registry.get('other','ayla');
    assert.notEqual(other.repository.config.instanceId,peer.repository.config.instanceId);
    assert.equal((await registry.get('other')).repository.config.instanceId,'u-other');
    const long = await registry.get('x'.repeat(64),'ayla');
    assert.ok(long.repository.config.instanceId.length <= 64);
    await assert.rejects(registry.get('../owner','ayla'),{status:400});
    await assert.rejects(registry.get('owner','other'),{status:400});
    await registry.shutdown();
});


test('reuses the saved user and assistant instance identifiers with owner and role isolation',async()=>{
    const calls=[],configs=[];
    const registry=new InstanceRegistry({config:{ownerId:'owner',instanceId:'new-default'},pool:{async query(sql,args){calls.push(args);return {rows:[{id:args[1]==='user'?'legacy-user':'legacy-assistant'}]};}},
        createRepository(pool,config){configs.push(config);return{};},createStore(){return{};},
        createManager(){return{async start(){},async shutdown(){}};}});
    await registry.get('owner');await registry.get('owner','ayla');
    assert.equal(configs[0].instanceId,'legacy-user');assert.equal(configs[1].instanceId,'legacy-assistant');
    assert.deepEqual(calls.map(args=>args.slice(0,2)),[['owner','user'],['owner','ayla']]);
    assert.equal(configs[0].connectionRole,'user');
    await registry.shutdown();
});
