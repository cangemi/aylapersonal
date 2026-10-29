const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const {loadGroupCreation,prepareGroupCreation,findPersonalGroupInPage}=require('../groupCreation');
const {setGroupPictureInPage}=require('../groupPicture');

test('recovery accepts only an owned active group confirmed to have one participant',async()=>{
    const id='120363000000000003@g.us';let size=1,serverCount=1;
    const chat={id:{server:'g.us',_serialized:id},name:'Ayla',isReadOnly:false,groupMetadata:{owner:'me',get size(){return size;}}};
    const window={require(name){
        if(name==='WAWebCollections')return{Chat:{getModelsArray:()=>[chat]}};
        if(name==='WAWebUserPrefsMeUser')return{isMeAccount:value=>value==='me'};
        return{queryGroupJob:async()=>({status:'success',groupInfo:{participants:Array(serverCount).fill({})}})};
    }};
    const run=()=>vm.runInNewContext(`(${findPersonalGroupInPage.toString()})()`,{window});
    assert.equal(await run(),id);
    serverCount=2;assert.equal(await run(),null);
    serverCount=1;size=2;assert.equal(await run(),null);
    size=1;chat.groupMetadata.owner='someone-else';assert.equal(await run(),null);
});

test('refreshes group permissions before setting the avatar without serializing messages or participants',async()=>{
    let updated=false;
    const id='120363000000000000@g.us',image={mimetype:'image/png',data:'test'};
    const pic={canSet:()=>true};let writes=0;
    const window={require(name){
        if(name==='WAWebCollections')return{WAWebGroupMetadataCollection:{async update(value){assert.equal(value,id);updated=true;},get:()=>({participants:{reset:members=>assert.equal(members.length,1)}})},ProfilePicThumb:{get:()=>pic}};
        if(name==='WAWebGroupQueryBridge')return{sendQueryGroup:async()=>({participants:[{}]})};
        if(name==='WAWebProfilePicThumbAction')return{async setProfilePic(value){assert.equal(updated,true);assert.equal(value.profilePicThumb,pic);assert.equal(value.thumb,'96');assert.equal(value.full,'640');writes++;}};
        return{createWid:value=>value};
    },setTimeout:fn=>{fn();return 1;}, WWebJS:{async cropAndResizeImage(media,options){assert.equal(media,image);return String(options.size);}}};
    const result=await vm.runInNewContext(`(${setGroupPictureInPage.toString()})(id,image)`,{window,id,image});
    assert.equal(result,true);
    assert.equal(writes,1);
    pic.canSet=()=>false;
    assert.equal(await vm.runInNewContext(`(${setGroupPictureInPage.toString()})(id,image)`,{window,id,image}),false);
    assert.equal(writes,1);
});

test('loads the lazy WhatsApp bundle before creation and skips loading when already available',async()=>{
    let loaded=false,calls=0;
    const window={require(name){
        if(name==='WAWebGroupCreateJob')return loaded?{createGroup(){throw new Error('must not create real group');}}:undefined;
        if(name==='WAWebNewGroupFlowLoadable')return{async requireBundle(){calls++;loaded=true;}};
    }};
    const page={evaluate:fn=>vm.runInNewContext(`(${fn.toString()})()`,{window})};
    await prepareGroupCreation(page);assert.equal(loaded,true);assert.equal(calls,1);
    await prepareGroupCreation(page);assert.equal(calls,1);
});
test('surfaces unavailable and incomplete bundles rather than swallowing the cause',async()=>{
    await assert.rejects(vm.runInNewContext(`(${loadGroupCreation.toString()})()`,{window:{require:()=>undefined}}),/bundle is unavailable/);
    const window={require:name=>name==='WAWebNewGroupFlowLoadable'?{async requireBundle(){}}:undefined};
    await assert.rejects(vm.runInNewContext(`(${loadGroupCreation.toString()})()`,{window}),/module did not load/);
});
