const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {EventEmitter} = require('node:events');
const {databaseAuth} = require('../databaseAuth');

test('preserves the pre-backup local login and prefers a ready local profile over an older checkpoint',async t=>{
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'ayla-auth-preserve-'));
    const profile=path.join(root,'RemoteAuth-test');
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    await fs.mkdir(path.join(profile,'Default','IndexedDB'),{recursive:true});
    await fs.writeFile(path.join(profile,'Default','IndexedDB','fixture'),'synthetic-login');
    await fs.writeFile(path.join(profile,'SingletonLock'),'stale-lock');
    let remote=false,extracts=0;
    class Remote {
        constructor(){this.dataPath=root;this.userDataDir=profile;this.sessionName='RemoteAuth-test';this.store={sessionExists:async()=>remote};this.client=new EventEmitter();}
        async extractRemoteSession(){extracts++;}
    }
    const Auth=databaseAuth(Remote),auth=new Auth();
    await auth.extractRemoteSession();
    assert.equal(await fs.readFile(path.join(profile,'Default','IndexedDB','fixture'),'utf8'),'synthetic-login');
    await assert.rejects(fs.stat(path.join(profile,'SingletonLock')),{code:'ENOENT'});
    await auth.markReady();remote=true;
    await auth.extractRemoteSession();assert.equal(extracts,0);
    await fs.unlink(path.join(profile,'ayla-session-ready'));
    await auth.extractRemoteSession();assert.equal(extracts,1);
});

test('serializes checkpoints, waits for them before destruction and reports an explicit checkpoint failure',async()=>{
    let release,concurrent=0,max=0,destroyed=false;
    class Remote {
        constructor(){this.client=new EventEmitter();this.client.on('remote_session_error',()=>{});}
        async storeRemoteSession(options){concurrent++;max=Math.max(max,concurrent);try{if(options.wait)await new Promise(resolve=>release=resolve);if(options.fail)throw new Error('checkpoint failed');}finally{concurrent--;}}
        async destroy(){destroyed=true;}
    }
    const Auth=databaseAuth(Remote),auth=new Auth();
    const first=auth.storeRemoteSession({wait:true});await new Promise(resolve=>setImmediate(resolve));
    const second=auth.storeRemoteSession();const closing=auth.destroy();
    assert.equal(destroyed,false);release();await Promise.all([first,second,closing]);
    assert.equal(max,1);assert.equal(destroyed,true);
    const other=new Auth();await assert.rejects(other.storeRemoteSession({fail:true,throwOnError:true}),/checkpoint failed/);
});
