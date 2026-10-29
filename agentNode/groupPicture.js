async function setGroupPictureInPage(id,image) {
    const collections=window.require('WAWebCollections');
    const metadata=collections.GroupMetadata||collections.WAWebGroupMetadataCollection;
    // Refresh membership before checking the native photo-edit permission.
    const wid=window.require('WAWebWidFactory').createWid(id);
    const current=await window.require('WAWebGroupQueryBridge').sendQueryGroup(wid);
    if(!Array.isArray(current?.participants))return false;
    await metadata.update(wid);
    const pic=collections.ProfilePicThumb.get(wid)||await collections.ProfilePicThumb.find(wid);
    // The native state bridge applies queried members asynchronously. Wait for
    // its permission cache; never replace participant models with raw records.
    for(let attempt=0;!pic?.canSet()&&attempt<20;attempt++)
        await new Promise(resolve=>window.setTimeout(resolve,100));
    if(!pic?.canSet()) return false;
    const thumb=await window.WWebJS.cropAndResizeImage(image,{asDataUrl:true,mimetype:'image/jpeg',size:96});
    const full=await window.WWebJS.cropAndResizeImage(image,{asDataUrl:true,mimetype:'image/jpeg',size:640});
    // The current native action resolves on success; sendSetPicture returns tag/eurl,
    // whereas the SDK's setPicture incorrectly expects response.status===200.
    await window.require('WAWebProfilePicThumbAction').setProfilePic({full,thumb,profilePicThumb:pic});
    return true;
}
async function applyGroupPicture(page,id,media) {
    return page.evaluate(setGroupPictureInPage,id,media);
}
module.exports={applyGroupPicture,setGroupPictureInPage};
