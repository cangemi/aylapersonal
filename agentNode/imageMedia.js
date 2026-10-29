const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
function isImage(message) {
    return Boolean(message.type === 'image' || message.type === 'sticker'
        || String(message.rawData?.mimetype || '').startsWith('image/'));
}
async function downloadImageFromPage(page, messageId, kind = 'image') {
    const result = await page.evaluate(async (id, maxBytes, kind) => {
        let stage = 'localizar mídia';
        try {
            // Only the message delivered by a live event: never fetch chat history.
            const msg = window.require('WAWebCollections').Msg.get(id);
            if (!msg?.directPath || !msg.mediaData || msg.mediaData.mediaStage === 'REUPLOADING') return null;
            const declaredMime = String(msg.mimetype || '').split(';')[0].trim().toLowerCase();
            const allowed = kind === 'document' ? ['application/pdf','application/msword','application/vnd.openxmlformats-officedocument.wordprocessingml.document','text/plain'] : kind === 'audio' ? ['audio/ogg', 'audio/mpeg', 'audio/mp4', 'audio/wav', 'audio/webm', 'audio/flac', 'audio/aac', 'audio/opus', 'audio/x-m4a'] : ['image/jpeg', 'image/png', 'image/webp'];
            if (!allowed.includes(declaredMime)) return null;
            if (msg.size > maxBytes) throw new Error('MEDIA_TOO_LARGE');
            stage = 'preparar mídia';
            if (msg.mediaData.mediaStage !== 'RESOLVED') {
                await msg.downloadMedia({ downloadEvenIfExpensive: true, rmrReason: 1, isUserInitiated: true });
            }
            stage = 'baixar e descriptografar';
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 30000);
            let bytes;
            try {
                bytes = await window.require('WAWebDownloadManager').downloadManager.downloadAndMaybeDecrypt({
                    directPath: msg.directPath, encFilehash: msg.encFilehash, filehash: msg.filehash,
                    mediaKey: msg.mediaKey, mediaKeyTimestamp: msg.mediaKeyTimestamp,
                    type: msg.type, mimetype: msg.mimetype,
                    signal: controller.signal,
                    downloadQpl: { addAnnotations() { return this; }, addPoint() { return this; } },
                });
            } finally { clearTimeout(timeout); }
            if (!bytes?.byteLength || bytes.byteLength > maxBytes) throw new Error('MEDIA_TOO_LARGE');
            stage = kind === 'audio' ? 'validar áudio' : 'validar imagem';
            const view = new Uint8Array(bytes);
            if (kind === 'document') {
                const pdf=String.fromCharCode(...view.slice(0,5))==='%PDF-';
                const zip=view[0]===80&&view[1]===75;
                const ole=[208,207,17,224].every((v,i)=>view[i]===v);
                if(!((declaredMime==='application/pdf'&&pdf)||(declaredMime.includes('wordprocessingml')&&zip)||(declaredMime==='application/msword'&&ole)||declaredMime==='text/plain')) throw new Error('InvalidDocumentSignature');
                return {mimetype:declaredMime,data:await window.WWebJS.arrayBufferToBase64Async(bytes)};
            }
            if (kind === 'audio') {
                const text = (at, length) => String.fromCharCode(...view.slice(at, at + length));
                const ogg = text(0, 4) === 'OggS';
                const valid = ogg || text(0, 3) === 'ID3' || (view[0] === 255 && (view[1] & 224) === 224)
                    || (text(0, 4) === 'RIFF' && text(8, 4) === 'WAVE') || text(0, 4) === 'fLaC'
                    || text(4, 4) === 'ftyp' || [26, 69, 223, 163].every((v, i) => view[i] === v);
                if (!valid) throw new Error('InvalidAudioSignature');
                return { mimetype: ogg ? 'audio/ogg' : declaredMime.replace('audio/x-m4a', 'audio/mp4'),
                    data: await window.WWebJS.arrayBufferToBase64Async(bytes) };
            }
            const jpeg = view[0] === 255 && view[1] === 216 && view[2] === 255;
            const png = [137, 80, 78, 71, 13, 10, 26, 10].every((value, index) => view[index] === value);
            const webp = view.length >= 12 && String.fromCharCode(...view.slice(0, 4)) === 'RIFF'
                && String.fromCharCode(...view.slice(8, 12)) === 'WEBP';
            const mimetype = jpeg ? 'image/jpeg' : png ? 'image/png' : webp ? 'image/webp' : null;
            if (!mimetype) throw new Error('InvalidImageSignature');
            return { mimetype, data: await window.WWebJS.arrayBufferToBase64Async(bytes) };
        } catch (error) {
            // Capture inside the browser so Puppeteer does not turn the error into "t: t".
            return { error: { stage, name: String(error?.name || 'MediaError'),
                message: String(error?.message || 'Falha no download').slice(0, 300) } };
        }
    }, messageId, kind === 'audio' || kind === 'document' ? 6 * 1024 * 1024 : MAX_IMAGE_BYTES, kind);
    if (result?.error) throw new Error(`${result.error.stage}: ${result.error.name}: ${result.error.message}`);
    return result;
}
async function downloadImage(message, client) {
    let media;
    try {
        media = client?.downloadImageMedia ? await client.downloadImageMedia(message) : await message.downloadMedia();
    } catch (cause) {
        throw Object.assign(new Error(cause?.message || 'Falha ao baixar imagem', { cause }), {
            userMessage: 'Não consegui baixar essa imagem. Pode reenviar como foto? Nenhum lançamento foi feito a partir dela.',
        });
    }
    const mimeType = media?.mimetype?.split(';')[0].toLowerCase().replace('image/jpg', 'image/jpeg');
    if (!media?.data || !['image/jpeg', 'image/png', 'image/webp'].includes(mimeType))
        throw Object.assign(new Error('Imagem indisponível ou formato não suportado'), { userMessage: 'Não consegui ler essa imagem. Pode reenviar como foto JPEG, PNG ou WebP?' });
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(media.data) || Buffer.byteLength(media.data, 'base64') > MAX_IMAGE_BYTES)
        throw Object.assign(new Error('Imagem excede o limite'), { userMessage: 'Pode reenviar a imagem com até 4 MB?' });
    return { mimeType, data: media.data };
}
function isAudio(message) {
    return message.type === 'ptt' || message.type === 'audio' || String(message.rawData?.mimetype || '').startsWith('audio/');
}
async function downloadAudio(message, client) {
    try {
        if (Number(message.rawData?.duration) > 300) throw new Error('Audio duration exceeds five minutes');
        const media = client?.downloadAudioMedia ? await client.downloadAudioMedia(message) : await message.downloadMedia();
        const mimeType = media?.mimetype?.split(';')[0].trim().toLowerCase().replace('audio/x-m4a', 'audio/mp4').replace('audio/opus', 'audio/ogg');
        if (!media?.data || !['audio/ogg', 'audio/mpeg', 'audio/mp4', 'audio/wav', 'audio/webm', 'audio/flac', 'audio/aac'].includes(mimeType)
                || !/^[A-Za-z0-9+/]+={0,2}$/.test(media.data) || Buffer.byteLength(media.data, 'base64') > 6 * 1024 * 1024)
            throw new Error('Invalid or oversized audio');
        return { mimeType, data: media.data };
    } catch (cause) {
        throw Object.assign(new Error(cause?.message || 'Falha ao baixar áudio', { cause }), {
            userMessage: 'Não consegui ler esse áudio. Pode reenviar com até 5 minutos e 6 MB, ou escrever a mensagem?',
        });
    }
}
const downloadAudioFromPage = (page, id) => downloadImageFromPage(page, id, 'audio');
function isDocument(message) { return message.type === 'document' && !isImage(message) && !isAudio(message); }
async function downloadDocument(message,client) {
    try {
        const media=client?.downloadDocumentMedia?await client.downloadDocumentMedia(message):await message.downloadMedia();
        const mimeType=String(media?.mimetype||'').split(';')[0].trim().toLowerCase();
        if(!['application/pdf','application/msword','application/vnd.openxmlformats-officedocument.wordprocessingml.document','text/plain'].includes(mimeType)
            || !media?.data || !/^[A-Za-z0-9+/]+={0,2}$/.test(media.data) || Buffer.byteLength(media.data,'base64')>6*1024*1024)throw new Error('Invalid document');
        const name=String(media.filename||message.rawData?.filename||'documento').split(/[\\/]/).at(-1).replace(/[\x00-\x1f]/g,'').slice(0,160)||'documento';
        return {mimeType,data:media.data,name};
    } catch(cause) {throw Object.assign(new Error('Document unavailable',{cause}),{userMessage:'Não consegui baixar esse documento. Reenvie em PDF, DOCX, DOC ou TXT com até 6 MB.'});}
}
module.exports = { isImage, downloadImage, downloadImageFromPage, isAudio, downloadAudio, downloadAudioFromPage,isDocument,downloadDocument };

