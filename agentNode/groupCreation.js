// WhatsApp Web loads group creation only when its new-group bundle is requested.
// wwebjs/whatsapp-web.js#201920: Client.createGroup hides this missing-module error.
async function loadGroupCreation() {
    const getJob=()=>window.require('WAWebGroupCreateJob');
    if(typeof getJob()?.createGroup==='function') return;
    const loader=window.require('WAWebNewGroupFlowLoadable');
    if(typeof loader?.requireBundle!=='function')
        throw new Error('WhatsApp group creation bundle is unavailable');
    await loader.requireBundle();
    if(typeof getJob()?.createGroup!=='function')
        throw new Error('WhatsApp group creation module did not load');
}
async function prepareGroupCreation(page) {
    await page.evaluate(loadGroupCreation);
}
async function personalGroupStateInPage(id) {
    const wid=window.require('WAWebWidFactory').createWid(id);
    const result=await window.require('WAWebGroupQueryGroupJob').queryGroupJob(wid,'out_of_sync_update');
    return result?.status||'unknown';
}
async function findPersonalGroupInPage() {
    const collections=window.require('WAWebCollections'),me=window.require('WAWebUserPrefsMeUser');
    const candidates=collections.Chat.getModelsArray().filter(chat=>{
        const group=chat.groupMetadata;
        return chat.id?.server==='g.us'&&(chat.name==='Ayla'||chat.formattedTitle==='Ayla')&&!chat.isReadOnly
            && group?.owner && me.isMeAccount(group.owner) && group.size===1;
    });
    const active=[];
    for(const chat of candidates) {
        const result=await window.require('WAWebGroupQueryGroupJob').queryGroupJob(chat.id,'out_of_sync_update');
        if(result?.status==='success' && result.groupInfo?.participants?.length===1)
            active.push(chat.id._serialized||chat.id.toString());
    }
    if(active.length>1)throw new Error('Multiple personal Ayla groups require manual selection');
    return active[0]||null;
}
module.exports={prepareGroupCreation,loadGroupCreation,personalGroupStateInPage,findPersonalGroupInPage};
