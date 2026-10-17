'use strict';
// Minimal Standard Webhooks receiver using only Node's standard library.
//
//   WEBHOOK_SECRET=whsec_... PORT=3000 node receiver.js

const http = require('node:http');
const crypto = require('node:crypto');

const TOLERANCE_SECONDS = 5 * 60;
const MAX_BODY_BYTES = 1024 * 1024;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

function header(headers, name) {
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === name) return Array.isArray(value) ? value[0] : value;
  }
  return undefined;
}

function secretKey(secret) {
  const encoded = secret.startsWith('whsec_') ? secret.slice('whsec_'.length) : secret;
  if (encoded.length % 4 !== 0 || !BASE64.test(encoded)) throw new Error('WEBHOOK_SECRET is not valid base64');
  return Buffer.from(encoded, 'base64');
}

/**
 * Verifies a delivery. `rawBody` must be the exact bytes received (a Buffer), never
 * JSON.stringify(JSON.parse(body)). `now` is unix seconds. Returns { valid, reason }.
 */
function verify(secret, headers, rawBody, now = Date.now() / 1000) {
  const id = header(headers, 'webhook-id');
  const timestamp = header(headers, 'webhook-timestamp');
  const signatures = header(headers, 'webhook-signature');
  if (id === undefined || timestamp === undefined || signatures === undefined) {
    return { valid: false, reason: 'missing_header' };
  }
  if (!/^[0-9]{1,15}$/.test(timestamp)) return { valid: false, reason: 'invalid_timestamp' };
  // Reject in both directions: an old timestamp is a replay, a future one a skewed or forged clock.
  if (Math.abs(Math.floor(now) - Number(timestamp)) > TOLERANCE_SECONDS) {
    return { valid: false, reason: 'timestamp_out_of_tolerance' };
  }
  const expected = crypto
    .createHmac('sha256', secretKey(secret))
    .update(`${id}.${timestamp}.`, 'utf8')
    .update(rawBody)
    .digest();
  // Several signatures are sent during a secret rotation; any one matching is enough.
  for (const entry of signatures.trim().split(/ +/)) {
    if (!entry.startsWith('v1,')) continue; // unknown version: ignore
    const encoded = entry.slice(3);
    if (encoded.length % 4 !== 0 || !BASE64.test(encoded)) continue; // malformed: skip, another may match
    const provided = Buffer.from(encoded, 'base64');
    // timingSafeEqual throws on a length mismatch; the length of an HMAC is not a secret.
    if (provided.length === expected.length && crypto.timingSafeEqual(provided, expected)) {
      return { valid: true, reason: 'ok' };
    }
  }
  return { valid: false, reason: 'no_matching_signature' };
}

/** Creates (does not start) the server. `onEvent({ webhookId, type, payload })` runs once per webhook-id. */
function createReceiver({ secret, onEvent = () => {}, log = console.log }) {
  secretKey(secret); // fail at startup, not per request, if the secret is malformed

  // Deliveries are at-least-once: the same webhook-id arrives again after a timeout, a retry or a
  // replay. This in-memory set forgets everything on restart and is not shared between instances;
  // production needs a durable store (e.g. a unique key in your database, written in the same
  // transaction as the side effect, or Redis SET NX with an expiry longer than the retry window).
  const seen = new Set();

  return http.createServer((req, res) => {
    const reply = (status, text) => res.writeHead(status, { 'content-type': 'text/plain' }).end(text);
    if (new URL(req.url, 'http://x').pathname !== '/webhooks') return reply(404, 'not found');
    if (req.method !== 'POST') return reply(405, 'method not allowed');

    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reply(413, 'payload too large');
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on('end', () => {
      const rawBody = Buffer.concat(chunks); // keep the raw bytes: verify these, parse afterwards
      const result = verify(secret, req.headers, rawBody);
      if (!result.valid) {
        log(`rejected delivery: ${result.reason}`);
        return reply(401, 'invalid signature');
      }
      const webhookId = req.headers['webhook-id'];
      if (seen.has(webhookId)) {
        log(`duplicate ${webhookId}, already processed`);
        return reply(200, 'duplicate');
      }
      seen.add(webhookId);
      try {
        const payload = JSON.parse(rawBody.toString('utf8'));
        // Keep this fast: the platform times out slow receivers and retries. Real work belongs on a
        // queue; acknowledge once the event is safely recorded.
        log(`received ${payload.type} ${webhookId}`);
        onEvent({ webhookId, type: payload.type, payload });
      } catch (err) {
        seen.delete(webhookId); // not handled, so let the platform's retry through
        log(`processing failed: ${err.message}`);
        return reply(500, 'error');
      }
      reply(200, 'ok');
    });
  });
}

module.exports = { verify, createReceiver, TOLERANCE_SECONDS };

if (require.main === module) {
  const secret = process.env.WEBHOOK_SECRET;
  if (!secret) {
    console.error('Set WEBHOOK_SECRET to the endpoint\'s signing secret (whsec_...).');
    process.exit(1);
  }
  const port = Number(process.env.PORT || 3000);
  createReceiver({ secret }).listen(port, () => console.log(`listening on http://localhost:${port}/webhooks`));
}
