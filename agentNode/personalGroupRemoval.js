async function inspectPersonalGroupInPage(chatId, leave=false) {
    const wid=window.require('WAWebWidFactory').createWid(chatId);
    const result=await window.require('WAWebGroupQueryGroupJob').queryGroupJob(wid,'out_of_sync_update');
    if(['terminated_local','terminated','not_member'].includes(result?.status))return {eligible:false,alreadyLeft:true};
    const participants=result?.groupInfo?.participants;
    const me=window.require('WAWebUserPrefsMeUser');
    const isMe=id=>{try{return Boolean(id&&me.isMeAccount(id));}catch{return false;}};
    const member=participants?.[0];
    const eligible=result?.status==='success'&&participants?.length===1
        &&[member?.id,member?.lid,member?.phoneNumber].some(isMe);
    if(leave){
        if(!eligible)throw new Error('Personal group membership changed');
        const chat=await window.WWebJS.getChat(chatId,{getAsModel:false});
        await window.require('WAWebExitGroupAction').sendExitGroup(chat);
        return {eligible:true,alreadyLeft:true};
    }
    return {eligible,alreadyLeft:false};
}
module.exports={inspectPersonalGroupInPage};
