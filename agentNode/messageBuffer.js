class MessageBuffer {
    constructor({ delayMs = 5000, onFlush, isTyping = async () => false, timers = { setTimeout, clearTimeout } }) {
        this.delayMs = delayMs; this.onFlush = onFlush; this.timers = timers; this.pending = new Map();
        this.isTyping=isTyping;
    }
    add(batch, message) {
        let current = this.pending.get(batch.chatId);
        if (current) {
            const images = [...current.messages, message].flatMap(m => m.images || []);
            const audios = [...current.messages, message].flatMap(m => m.audios || []);
            const documents = [...current.messages, message].flatMap(m => m.documents || []);
            const audioBytes = audios.reduce((sum, audio) => sum + Buffer.byteLength(audio.data, 'base64'), 0);
            const bytes = images.reduce((sum, image) => sum + Buffer.byteLength(image.data, 'base64'), 0);
            if (images.length > 3 || bytes > 6 * 1024 * 1024 || audios.length > 3 || audioBytes > 12 * 1024 * 1024 || documents.length>2
                    || [...current.messages, message].map(m => m.body || '').join('\n').length > 32000) {
                this.timers.clearTimeout(current.timer); this.pending.delete(batch.chatId);
                Promise.resolve(this.onFlush(current)).catch(() => {});
                current = null;
            }
        }
        if (!current) { current = { ...batch, messages: [] }; this.pending.set(batch.chatId, current); }
        current.messages.push(message);
        if(this.delayMs===0){this.pending.delete(batch.chatId);return this.onFlush(current);}
        this.schedule(current,current.ownerTyping?1000:this.delayMs);
    }
    schedule(batch,delay) {
        this.timers.clearTimeout(batch.timer);
        batch.revision=(batch.revision||0)+1;
        const revision=batch.revision;
        batch.timer=this.timers.setTimeout(()=>{void this.flush(batch,revision).catch(()=>{});},delay);
    }
    typing(chatId,active) {
        const batch=this.pending.get(chatId);
        if(!batch)return;
        batch.ownerTyping=Boolean(active);
        this.schedule(batch,active?1000:this.delayMs);
    }
    async flush(batch,revision) {
        if(this.pending.get(batch.chatId)!==batch || batch.revision!==revision)return;
        let typing=false;
        try {typing=await this.isTyping(batch.chatId);}catch {/* Presence is optional; never invent activity. */}
        if(this.pending.get(batch.chatId)!==batch || batch.revision!==revision)return;
        if(typing){batch.ownerTyping=true;this.schedule(batch,1000);return;}
        if(batch.ownerTyping){batch.ownerTyping=false;this.schedule(batch,this.delayMs);return;}
        this.pending.delete(batch.chatId);
        return this.onFlush(batch);
    }
    cancel(chatId) {
        const batch = this.pending.get(chatId);
        if (!batch) return [];
        this.timers.clearTimeout(batch.timer); this.pending.delete(chatId);
        return batch.messages;
    }
    clear() { return [...this.pending.keys()].flatMap(id => this.cancel(id)); }
}
module.exports = { MessageBuffer };


