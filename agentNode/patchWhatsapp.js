// Compatibility fix for whatsapp-web.js #201921/#201923 on current WhatsApp Web.
// Media JSON leaks __x_id into the outgoing message and masks the new message ID.
const fs=require('node:fs'),path=require('node:path');
const source=path.join(path.dirname(require.resolve('whatsapp-web.js/package.json')),'src/util/Injected/Utils.js');
const text=fs.readFileSync(source,'utf8');
if(!text.includes('delete message.__x_id;')) {
    const anchor="        // Bot's won't reply if canonicalUrl is set (linking)";
    if(!text.includes(anchor)) throw new Error('WhatsApp media compatibility patch needs review for this SDK version.');
    fs.writeFileSync(source,text.replace(anchor,'        delete message.__x_id;\n\n'+anchor));
}
