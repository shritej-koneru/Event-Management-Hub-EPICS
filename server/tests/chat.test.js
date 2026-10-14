import http from 'node:http';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { io as ioClient } from 'socket.io-client';
import { as, api, loginDemo, testContext, unique } from './helpers/testContext.js';
import { attachSocketServer } from '../src/socket/index.js';
import { countChatMessages, listRecentChatMessages } from '../src/models/chatMessageModel.js';
import { query } from '../src/db/pool.js';

/**
 * Interactive chat persistence: Socket.IO write path + GET /api/chat/messages
 * history. Complements tests/socket.test.js (rooms / announcements) which never
 * emits `chat:message`.
 */

let server;
let io;
let baseUrl;
let token;
const openSockets = [];

beforeAll(async () => {
  const { app } = testContext();
  ({ token } = await loginDemo());

  server = http.createServer(app);
  io = attachSocketServer(server);
  await new Promise((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

afterAll(async () => {
  for (const socket of openSockets) socket.close();
  await new Promise((resolve) => io.close(resolve));
  await new Promise((resolve) => server.close(resolve));
});

function connect(authToken) {
  const socket = ioClient(baseUrl, {
    path: '/socket.io',
    transports: ['websocket'],
    ...(authToken ? { auth: { token: authToken } } : {})
  });
  openSockets.push(socket);
  return socket;
}

const waitFor = (socket, event, timeout = 5000) =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for "${event}"}`)), timeout);
    socket.once(event, (...args) => {
      clearTimeout(timer);
      resolve(args);
    });
  });

function emitAck(socket, event, payload) {
  return new Promise((resolve) => socket.emit(event, payload, resolve));
}

describe('chat:message persistence (Socket.IO)', () => {
  it('acks ok, stores the row, and broadcasts the same message', async () => {
    const marker = unique('hello');
    const socket = connect();
    await waitFor(socket, 'connect');

    const broadcast = waitFor(socket, 'chat:message');
    const ack = await emitAck(socket, 'chat:message', { text: marker });
    const [echoed] = await broadcast;

    expect(ack.ok).toBe(true);
    expect(ack.message.text).toBe(marker);
    expect(ack.message.author).toBe('Guest');
    expect(ack.message.id).toBeDefined();
    expect(ack.message.ts).toBeDefined();
    expect(echoed).toEqual(ack.message);

    const stored = await listRecentChatMessages({ limit: 5 });
    expect(stored.some((m) => m.text === marker)).toBe(true);
  });

  it('attributes an authenticated message to the signed-in user', async () => {
    const marker = unique('authored');
    const socket = connect(token);
    await waitFor(socket, 'connect');

    const ack = await emitAck(socket, 'chat:message', { text: marker });
    expect(ack.ok).toBe(true);
    expect(ack.message.author).not.toBe('Guest');
    expect(ack.message.author.length).toBeGreaterThan(0);
  });

  it('stores a room-scoped message and filters history by that room', async () => {
    const marker = unique('roomed');
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await emitAck(socket, 'chat:message', { text: marker, room: 'library' });
    expect(ack.ok).toBe(true);
    expect(ack.message.room).toBe('library');

    const { body } = await api().get('/api/chat/messages?room=library&limit=100');
    expect(body.messages.some((m) => m.text === marker)).toBe(true);

    const global = await listRecentChatMessages({ limit: 100 });
    expect(global.some((m) => m.text === marker && m.room === 'library')).toBe(true);
  });

  it('rejects an empty message without writing a row', async () => {
    const before = await countChatMessages();
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await emitAck(socket, 'chat:message', { text: '   ' });
    expect(ack.ok).toBe(false);
    expect(ack.error.code).toBe('BAD_CHAT_MESSAGE');
    expect(await countChatMessages()).toBe(before);
  });

  it('rejects a message longer than 500 characters', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await emitAck(socket, 'chat:message', { text: 'x'.repeat(501) });
    expect(ack.ok).toBe(false);
    expect(ack.error.code).toBe('BAD_CHAT_MESSAGE');
  });

  it('persists the assistant auto-reply so it survives reconnect', async () => {
    const marker = unique('botseed');
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await emitAck(socket, 'chat:message', { text: `${marker} hello` });
    expect(ack.ok).toBe(true);

    // The auto-reply is written on a 500 ms timer.
    await new Promise((resolve) => setTimeout(resolve, 900));

    const rows = await listRecentChatMessages({ limit: 20 });
    const botReply = rows.find((m) => m.bot === true && m.author === 'Event Hub Assistant');
    expect(botReply).toBeDefined();
    expect(typeof botReply.text).toBe('string');
    expect(botReply.text.length).toBeGreaterThan(0);
  });
});

describe('GET /api/chat/messages history', () => {
  it('returns messages oldest-first and includes a just-sent row', async () => {
    const marker = unique('hist');
    const socket = connect(token);
    await waitFor(socket, 'connect');
    await emitAck(socket, 'chat:message', { text: marker });

    const { status, body } = await api().get('/api/chat/messages?limit=100');
    expect(status).toBe(200);
    expect(Array.isArray(body.messages)).toBe(true);
    expect(body.messages.length).toBeGreaterThan(0);

    const texts = body.messages.map((m) => m.text);
    expect(texts).toContain(marker);

    const timestamps = body.messages.map((m) => m.ts);
    const sorted = [...timestamps].sort();
    expect(timestamps).toEqual(sorted);
  });

  it('rejects an unknown room rather than returning an empty list', async () => {
    const { status, body } = await api().get('/api/chat/messages?room=space-elevator');
    expect(status).toBe(400);
    expect(body.code).toBe('UNKNOWN_VENUE');
  });

  it('rejects unsupported query parameters', async () => {
    const { status } = await api().get('/api/chat/messages?nope=1');
    expect(status).toBe(400);
  });

  it('paginates with the before cursor', async () => {
    const a = unique('pageA');
    const b = unique('pageB');
    const socket = connect();
    await waitFor(socket, 'connect');
    await emitAck(socket, 'chat:message', { text: a });
    await new Promise((resolve) => setTimeout(resolve, 25));
    const second = await emitAck(socket, 'chat:message', { text: b });

    // `before` is exclusive: rows with at < second.ts (so `a`, not `b`).
    const page = await api().get(`/api/chat/messages?before=${encodeURIComponent(second.message.ts)}&limit=100`);
    expect(page.status).toBe(200);
    const texts = page.body.messages.map((m) => m.text);
    expect(texts).toContain(a);
    expect(texts).not.toContain(b);
  });

  it('rejects a malformed before timestamp', async () => {
    const { status } = await api().get('/api/chat/messages?before=not-a-date');
    expect(status).toBe(400);
  });

  it('works for an anonymous caller (history matches anonymous chat)', async () => {
    const { status, body } = await api().get('/api/chat/messages?limit=5');
    expect(status).toBe(200);
    expect(Array.isArray(body.messages)).toBe(true);
  });

  it('does not leak JWT secrets onto stored rows', async () => {
    const marker = unique('nosecret');
    const socket = connect(token);
    await waitFor(socket, 'connect');
    await emitAck(socket, 'chat:message', { text: marker });

    const { rows } = await query(
      'SELECT text, author, bot FROM chat_messages WHERE text = $1',
      [marker]
    );
    expect(rows).toHaveLength(1);
    const serialized = JSON.stringify(rows[0]);
    expect(serialized).not.toMatch(/eyJ|Bearer|password|secret123/i);
  });
});

describe('announcements still work alongside chat persistence', () => {
  it('GET /api/chat/announcements remains available', async () => {
    const { status, body } = await api().get('/api/chat/announcements');
    expect(status).toBe(200);
    expect(Array.isArray(body.announcements)).toBe(true);
  });

  it('POST /api/chat/join still validates venues', async () => {
    const { status } = await api().post('/api/chat/join').send({ venueId: 'space-elevator' });
    expect(status).toBe(400);
  });

  it('authenticated REST helper can read history too', async () => {
    const { status, body } = await as(token).get('/api/chat/messages?limit=1');
    expect(status).toBe(200);
    expect(Array.isArray(body.messages)).toBe(true);
  });
});
