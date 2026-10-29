const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { downloadImage, downloadImageFromPage } = require('../imageMedia');
const { messageKey } = require('../messageIdentity');

function pageFixture({ audio = false, bytes = Uint8Array.from([255, 216, 255, 224, 0, 0]).buffer, failure } = {}) {
    const canonical = 'true_120363000000000000@g.us_AAA_out';
    const message = { id: { $1: canonical }, type: audio ? 'ptt' : 'image', hasMedia: true };
    let calls = 0, prepared = 0;
    const model = { directPath: '/test', type: audio ? 'ptt' : 'image', mimetype: audio ? 'audio/ogg; codecs=opus' : 'image/jpeg', size: 6,
        mediaKey: 'test-key', mediaData: { mediaStage: 'INIT' },
        async downloadMedia() { prepared++; this.mediaData.mediaStage = 'RESOLVED'; } };
    const window = {
        require(name) {
            if (name === 'WAWebCollections') return { Msg: { get(id) { assert.equal(id, canonical); return model; } } };
            if (name === 'WAWebDownloadManager') return { downloadManager: {
                async downloadAndMaybeDecrypt(options) {
                    calls++;
                    assert.equal(options.mimetype, audio ? 'audio/ogg; codecs=opus' : 'image/jpeg');
                    assert.equal(options.type, audio ? 'ptt' : 'image');
                    assert.equal(typeof options.downloadQpl.addPoint, 'function');
                    if (failure) throw failure;
                    return bytes;
                },
            } };
            throw new Error('Unexpected module');
        },
        WWebJS: { async arrayBufferToBase64Async(value) { return Buffer.from(value).toString('base64'); } },
    };
    const page = { async evaluate(fn, ...args) {
        return vm.runInNewContext('(' + fn.toString() + ')(...args)', { window, args, AbortController, setTimeout, clearTimeout, Uint8Array });
    } };
    return { message, page, calls: () => calls, prepared: () => prepared };
}

test('native image download forwards MIME, uses complete $1 ID, decrypts once and validates JPEG bytes', async () => {
    const f = pageFixture();
    const image = await downloadImage(f.message, {
        downloadImageMedia: message => downloadImageFromPage(f.page, messageKey(message)),
    });
    assert.equal(image.mimeType, 'image/jpeg');
    assert.equal(Buffer.from(image.data, 'base64').length, 6);
    assert.equal(f.calls(), 1); assert.equal(f.prepared(), 1);
});

test('browser download errors stay legible; invalid bytes are rejected and request resend without implying a financial write', async () => {
    for (const options of [{ failure: { name: 'InvalidMediaFileType', message: 'Unexpected mimetype' } },
        { bytes: new ArrayBuffer(0) },
        { bytes: Uint8Array.from([1, 2, 3, 4]).buffer }]) {
        const f = pageFixture(options);
        await assert.rejects(() => downloadImage(f.message, {
            downloadImageMedia: message => downloadImageFromPage(f.page, messageKey(message)),
        }), error => {
            assert.match(error.message, /baixar e descriptografar|validar imagem/);
            assert.match(error.userMessage, /reenviar como foto/);
            assert.match(error.userMessage, /Nenhum lançamento foi feito a partir dela/);
            return true;
        });
    }
});


test('voice note decrypts with original Opus MIME and validates Ogg bytes; invalid audio asks for resend', async () => {
    const {downloadAudio, downloadAudioFromPage}=require('../imageMedia');
    const f=pageFixture({audio:true,bytes:Uint8Array.from([79,103,103,83,0,0,0,0]).buffer});
    const audio=await downloadAudio(f.message,{downloadAudioMedia:message=>downloadAudioFromPage(f.page,messageKey(message))});
    assert.equal(audio.mimeType,'audio/ogg');
    assert.equal(f.calls(),1);
    await assert.rejects(()=>downloadAudio({rawData:{duration:301}}),e=>/reenviar/.test(e.userMessage));
});
