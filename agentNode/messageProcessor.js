const axios = require('axios');
const { randomUUID } = require('node:crypto');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const HEADER = '-------Ayla--------';
function formatReply(text) {
    const body = String(text || '').trim().replace(/^-+\s*ayla\s*-+\s*/i, '').trim();
    return `${HEADER}\n\n${body}`;
}
function createMessageProcessor(config, http = axios, wait = sleep) {
    const active = new Set();
    const unconfirmed = new Map();
    const headers = contact => ({ Authorization: `Bearer ${config.apiKey}`, 'X-Ayla-WhatsApp-Chat': contact.id, 'X-Ayla-WhatsApp-Generation': contact.historyResetAt ? new Date(contact.historyResetAt).toISOString() : 'none' });
    async function waitForJob(initial, contact) {
        let job = initial;
        active.add(job.id);
        try {
            while (['QUEUED', 'RUNNING'].includes(job.status)) {
                await wait(1000);
                try { job = (await http.get(`${config.javaUrl}/api/v1/chat/jobs/${job.id}`, { headers: headers(contact), timeout: 15000 })).data; }
                catch (error) { if (error.response && ![502, 503, 504].includes(error.response.status)) throw error; }
            }
            const result = job.status === 'COMPLETED' ? job.result : { response: job.message || 'Não consegui concluir esse pedido.' };
            return { text: String(result.response || '').trim(), ...(result.audio ? { audio: result.audio, textSupplement:result.textSupplement } : {}), jobId: job.id,
                release: () => active.delete(job.id),
                acknowledge: async () => {
                    unconfirmed.set(job.id, contact);
                    try { await http.post(`${config.javaUrl}/api/v1/chat/jobs/${job.id}/ack`, null, { headers: headers(contact), timeout: 15000 }); unconfirmed.delete(job.id); }
                    catch { /* Retry only the acknowledgement, never the inference or the delivered message. */ }
                } };
        } catch (error) { active.delete(job.id); throw error; }
    }
    const processMessage = async function ({ text, contact, images = [], audios = [], documents = [], whatsappContext, requestKey, onQueued }) {
        const body = { message: text?.trim() || (audios.length ? 'Mensagem de áudio enviada pelo WhatsApp.' : documents.length?'Analise o documento enviado conforme o contexto.':'Analise a imagem enviada e responda conforme o contexto.') };
        if (images.length) body.images = images;
        if (audios.length) body.audios = audios;
        if (documents.length) body.documents = documents;
        if (whatsappContext) body.whatsappContext = whatsappContext;
        let response;
        try {
            response = await http.post(`${config.javaUrl}/api/v1/chat`, body, {
                timeout: 30000,
                headers: { ...headers(contact), Prefer: 'respond-async', 'Idempotency-Key': requestKey || randomUUID() },
            });
        } catch (error) {
            // Only these explicit STT failures guarantee the financial agent has not run.
            const preAgentFailures = new Set([
                'Configure a senha do painel Hermes para transcrever áudios.',
                'Não consegui autenticar a transcrição no Hermes.',
                'O serviço local de mídia do Hermes está indisponível.',
                'O Hermes não conseguiu concluir a operação de mídia agora.',
                'A transcrição deve estar configurada como local no Hermes.',
                'Não consegui entender o áudio. Pode repetir ou escrever a mensagem?',
            ]);
            if (audios.length && error.response?.data?.provider === 'hermes'
                    && [422, 503].includes(error.response.status)
                    && preAgentFailures.has(error.response.data.response)) {
                error.userMessage = 'Não consegui transcrever esse áudio agora. Pode reenviar ou escrever a mensagem?';
            }
            throw error;
        }
        onQueued?.();
        if (response.data.id && response.data.status) return waitForJob(response.data, contact);
        const reply = String(response.data.response || '').trim();
        if (!reply) throw new Error('A API retornou uma resposta vazia.');
        const audio=response.data.audio;
        if (audio && ['audio/ogg','audio/mpeg'].includes(audio.mimeType)
                && typeof audio.data === 'string' && /^[A-Za-z0-9+/]+={0,2}$/.test(audio.data)
                && Buffer.byteLength(audio.data,'base64') <= 6*1024*1024) return {text:reply,audio,textSupplement:response.data.textSupplement};
        return reply;
    };
    processMessage.recover = async contact => {
        for (const [id, saved] of unconfirmed) {
            if (saved.id !== contact.id) continue;
            try { await http.post(`${config.javaUrl}/api/v1/chat/jobs/${id}/ack`, null, { headers: headers(contact), timeout: 15000 }); unconfirmed.delete(id); } catch {}
        }
        const rows = (await http.get(`${config.javaUrl}/api/v1/chat/jobs`, { headers: headers(contact), timeout: 15000 })).data;
        const job = Array.isArray(rows) ? rows.find(row => !active.has(row.id) && !unconfirmed.has(row.id)) : null;
        return job ? waitForJob(job, contact) : null;
    };
    return processMessage;
}
module.exports = { createMessageProcessor, formatReply, HEADER };


