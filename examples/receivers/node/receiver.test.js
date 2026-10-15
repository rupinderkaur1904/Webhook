'use strict';
// Run with: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { verify, createReceiver } = require('./receiver');

// Signed by the platform's own signer, shared with the Java and Python examples.
const { vectors } = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'test-vectors.json'), 'utf8'));

for (const v of vectors) {
  test(`vector ${v.name}`, () => {
    const result = verify(v.secret, v.headers, Buffer.from(v.body, 'utf8'), v.now);
    assert.deepEqual(result, v.expected, v.description);
  });
}

test('server: valid -> 2xx, tampered -> 401, duplicate webhook-id acknowledged but processed once', async (t) => {
  const { secret, body, headers } = vectors.find((v) => v.name === 'valid');
  const id = headers['webhook-id'];
  // The vectors use a fixed 'now'; re-sign at the current time so the timestamp check passes.
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = 'v1,' + crypto
    .createHmac('sha256', Buffer.from(secret.slice('whsec_'.length), 'base64'))
    .update(`${id}.${timestamp}.${body}`, 'utf8')
    .digest('base64');

  const processed = [];
  const server = createReceiver({ secret, onEvent: (e) => processed.push(e), log: () => {} });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const url = `http://127.0.0.1:${server.address().port}/webhooks`;

  const post = (webhookId, payload) => fetch(url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'webhook-id': webhookId,
      'webhook-timestamp': timestamp,
      'webhook-signature': signature,
    },
    body: payload,
  }).then((r) => r.status);

  const first = await post(id, body);
  assert.ok(first >= 200 && first < 300, `valid request: ${first}`);
  assert.equal(await post(`tampered-${id}`, body.replace('42.5', '42.9')), 401);
  const dup = await post(id, body);
  assert.ok(dup >= 200 && dup < 300, `duplicate: ${dup}`);
  assert.equal(processed.length, 1);
  assert.equal(processed[0].type, 'order.created');
});
