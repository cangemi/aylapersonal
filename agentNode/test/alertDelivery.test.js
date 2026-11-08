const { test } = require('node:test');
const assert = require('node:assert/strict');
const { WhatsappManager } = require('../whatsappManager');

function fixture(row) {
    const sent = [];
    const queries = [];
    const client = {};
    const manager = Object.assign(Object.create(WhatsappManager.prototype), {
        config: { ownerId: 'owner' }, client, phase: 'ready', stopping: false,
        integrationActive: () => true,
        repository: {
            accountEnabled: async () => true,
            list: async () => [{ enabled: true, mentionOnly: false, chatId: 'primary@g.us' }],
            pool: { query: async (sql) => {
                queries.push(sql);
                return sql.startsWith('select') ? { rows: [row] } : { rowCount: 1 };
            } },
        },
        delivery: async (chatId) => ({ client, chatId, active: () => true }),
        sendText: async (_client, chatId, text) => sent.push({ chatId, text }),
    });
    return { manager, sent, queries };
}

test('calendar reminder reaches the primary WhatsApp conversation', async () => {
    const { manager, sent } = fixture({ whatsapp_status: 'PENDING', title: 'Google meeting',
        starts_at: new Date('2026-11-05T12:00:00Z'), time_zone: 'America/Sao_Paulo' });
    assert.equal((await manager.sendCalendarReminder('reminder')).status, 'SENT');
    assert.equal(sent[0].chatId, 'primary@g.us');
    assert.match(sent[0].text, /Google meeting/);
});

test('alarm delivery includes its confirmation instruction', async () => {
    const { manager, sent, queries } = fixture({ whatsapp_status: 'PENDING', title: 'Acordar', confirm_needed: true });
    assert.equal((await manager.sendAlarmNotification('alarm')).status, 'SENT');
    assert.match(queries[0], /a\.confirm_needed/);
    assert.match(sent[0].text, /Responda "ok"/);
});
