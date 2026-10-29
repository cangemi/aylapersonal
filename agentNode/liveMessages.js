// Supplement the library's isNewMsg-dependent event with live collection
// notifications. No history is fetched or replayed; manager checks original dates.
async function installLiveMessages(client) {
    const { Message } = require('whatsapp-web.js');
    const page = client.pupPage;
    if (!await page.evaluate(() => typeof window.onAylaLiveMessage === 'function')) {
        await page.exposeFunction('onAylaLiveMessage', raw => {
            client.emit('message_create', new Message(client, raw));
        });
    }
    await page.evaluate(() => {
        const { Msg } = window.require('WAWebCollections');
        if (window.aylaLiveMessages?.collection === Msg) return;
        const previous = window.aylaLiveMessages;
        if (previous) previous.collection.off('add change', previous.handler);
        const seen = new Set();
        const handler = model => {
            if (!model?.id || model.type === 'ciphertext' || model.type === 'revoked' || model.type === 'reaction') return;
            const image = model.type === 'image' || model.type === 'sticker' || String(model.mimetype || '').startsWith('image/');
            const audio = model.type === 'audio' || model.type === 'ptt' || String(model.mimetype || '').startsWith('audio/');
            const document = model.type === 'document';
            if ((image || audio || document) && !model.directPath) return; // Wait for downloadable media.
            if (!(model.body || model.caption || audio || document || model.type === 'image' || model.type === 'sticker'
                    || String(model.mimetype || '').startsWith('image/'))) return;
            const remote = model.id.remote;
            const remoteId = typeof remote === 'string' ? remote : remote?._serialized || (remote?.user && remote?.server ? `${remote.user}@${remote.server}` : null);
            const contextCutoff=window.aylaContextReadCutoffs?.get(remoteId);
            if(contextCutoff!==undefined && Number(model.t)<=contextCutoff) return;
            const participant = model.id.participant;
            const participantId = typeof participant === 'string' ? participant : participant?._serialized || (participant?.user && participant?.server ? `${participant.user}@${participant.server}` : null);
            const id = model.id._serialized || model.id.$1 || (remoteId && model.id.id
                ? [String(Boolean(model.id.fromMe)), remoteId, model.id.id, ...(participantId ? [participantId] : [])].join('_') : null);
            if (!id || seen.has(id)) return;
            try {
                const raw = window.WWebJS.getMessageModel(model);
                raw.id = { ...raw.id, _serialized: id };
                seen.add(id);
                if (seen.size > 5000) seen.delete(seen.values().next().value);
                Promise.resolve(window.onAylaLiveMessage(raw)).catch(() => seen.delete(id));
            } catch { /* A partial model may become readable on its next change. */ }
        };
        Msg.on('add change', handler);
        window.aylaLiveMessages = { collection: Msg, handler };
    });
}
module.exports = { installLiveMessages };

