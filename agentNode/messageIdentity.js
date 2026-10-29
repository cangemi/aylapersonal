function wid(value) {
    if (typeof value === 'string') return value;
    if (typeof value?._serialized === 'string') return value._serialized;
    if (typeof value?.$1 === 'string') return value.$1;
    if (value?.user && value?.server) return `${value.user}@${value.server}`;
    return null;
}
function messageKey(message) {
    const id = message.id;
    if (typeof id?._serialized === 'string' && id._serialized) return id._serialized;
    if (typeof id?.$1 === 'string' && id.$1) return id.$1;
    const remote = wid(id?.remote) || wid(message.fromMe ? message.to : message.from);
    if (!remote || typeof id?.id !== 'string' || !id.id) return null;
    const parts = [String(Boolean(id.fromMe ?? message.fromMe)), remote, id.id];
    if (id.participant) parts.push(wid(id.participant) || String(id.participant));
    return parts.join('_');
}
module.exports = { wid, messageKey };
