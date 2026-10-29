const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createMessageProcessor } = require('../messageProcessor');

test('WhatsApp posts once, waits through temporary read failures and acknowledges only after delivery', async () => {
    let submitted = 0, reads = 0, acknowledged = 0;
    const contact = { id: 'contact' };
    const job = { id: 'job', status: 'QUEUED' };
    const processor = createMessageProcessor({ javaUrl: 'http://java', apiKey: 'private' }, {
        async post(url, body, options) {
            if (url.endsWith('/ack')) { acknowledged++; return { data: {} }; }
            submitted++;
            assert.equal(options.headers.Prefer, 'respond-async');
            assert.equal(options.headers['Idempotency-Key'], 'stable-request');
            return { data: job };
        },
        async get() {
            if (++reads === 1) throw Object.assign(new Error('offline'), { response: { status: 503 } });
            return { data: { ...job, status: 'COMPLETED', result: { response: 'Resposta pronta' } } };
        },
    }, async () => {});
    const answer = await processor({ text: 'Oi', contact, requestKey: 'stable-request' });
    assert.equal(answer.text, 'Resposta pronta');
    assert.equal(submitted, 1); assert.equal(reads, 2); assert.equal(acknowledged, 0);
    await answer.acknowledge();answer.release();assert.equal(acknowledged, 1);
});

test('after restart the processor retrieves the saved answer without resubmitting the agent', async () => {
    let submitted = 0;
    const processor = createMessageProcessor({ javaUrl: 'http://java', apiKey: 'private' }, {
        async get() { return { data: [{ id: 'saved', status: 'COMPLETED', result: { response: 'Já concluído' } }] }; },
        async post() { submitted++;return { data: {} }; },
    }, async () => {});
    const answer = await processor.recover({ id: 'contact' });
    assert.equal(answer.text, 'Já concluído');assert.equal(submitted, 0);answer.release();
});
