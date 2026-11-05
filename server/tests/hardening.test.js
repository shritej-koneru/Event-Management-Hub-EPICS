import http from 'node:http';
import jwt from 'jsonwebtoken';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { io as ioClient } from 'socket.io-client';
import { api, as, loginDemo, testContext, unique } from './helpers/testContext.js';
import { attachSocketServer, socketCorsOrigin, CHAT_THROTTLE_MAX } from '../src/socket/index.js';
import { setUserRole } from '../src/models/userModel.js';
import { countChatMessages } from '../src/models/chatMessageModel.js';
import { signToken, verifyToken } from '../src/auth/jwt.js';
import { config } from '../src/config/index.js';
import { query } from '../src/db/pool.js';

const future = (days = 400) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);

const eventPayload = (over = {}) => ({
  title: unique('Hardening'),
  category: 'Tech',
  venue: 'Room 205',
  date: future(),
  time: '10:00',
  capacity: 20,
  ...over
});

async function register(role) {
  const email = `${unique('u')}@uni.edu`;
  const res = await api().post('/api/auth/register').send({ name: 'Role Test', email, password: 'secret123' });
  expect(res.status).toBe(201);
  if (role) await setUserRole(res.body.user.id, role);
  return { token: res.body.token, user: res.body.user };
}

describe('roles and event ownership (I-S1, I-S16)', () => {
  it('registers users as students and exposes role on /auth/me', async () => {
    const { token, user } = await register();
    expect(user.role).toBe('student');
    const me = await as(token).get('/api/auth/me');
    expect(me.body.user.role).toBe('student');
  });

  it('the seeded demo user is an admin', async () => {
    const { user } = await loginDemo();
    expect(user.role).toBe('admin');
  });

  it('forbids a student from creating an event (403 FORBIDDEN)', async () => {
    const { token } = await register();
    const res = await as(token).post('/api/events').send(eventPayload());
    expect(res.status).toBe(403);
    expect(res.body.code).toBe('FORBIDDEN');
  });

  it('lets an organizer create, update and delete their own event', async () => {
    const { token } = await register('organizer');
    const created = await as(token).post('/api/events').send(eventPayload());
    expect(created.status).toBe(201);
    const id = created.body.event.id;

    const updated = await as(token).put(`/api/events/${id}`).send({ capacity: 77, title: unique('Renamed') });
    expect(updated.status).toBe(200);
    expect(updated.body.event.capacity).toBe(77);

    const deleted = await as(token).delete(`/api/events/${id}`);
    expect(deleted.status).toBe(200);
    expect(deleted.body).toEqual({ deleted: true, eventId: id });
    expect((await api().get(`/api/events/${id}`)).status).toBe(404);
  });

  it('stops another organizer from touching the event, but allows an admin', async () => {
    const owner = await register('organizer');
    const other = await register('organizer');
    const admin = await register('admin');
    const id = (await as(owner.token).post('/api/events').send(eventPayload())).body.event.id;

    expect((await as(other.token).put(`/api/events/${id}`).send({ capacity: 5 })).status).toBe(403);
    expect((await as(other.token).delete(`/api/events/${id}`)).status).toBe(403);
    expect((await as(admin.token).put(`/api/events/${id}`).send({ capacity: 6 })).status).toBe(200);
    expect((await as(admin.token).delete(`/api/events/${id}`)).status).toBe(200);
  });

  it('PUT/DELETE: 404 for unknown id, 400 for empty body, 401 anonymous', async () => {
    const { token } = await register('admin');
    expect((await as(token).put('/api/events/evt_missing').send({ capacity: 1 })).status).toBe(404);
    expect((await as(token).delete('/api/events/evt_missing')).status).toBe(404);
    expect((await as(token).put('/api/events/evt_001').send({})).status).toBe(400);
    expect((await api().put('/api/events/evt_001').send({ capacity: 1 })).status).toBe(401);
  });
});

describe('event validation (I-S10)', () => {
  it('rejects a date in the past', async () => {
    const { token } = await loginDemo();
    const res = await as(token).post('/api/events').send(eventPayload({ date: '2020-01-01' }));
    expect(res.status).toBe(400);
    expect(res.body.code).toBe('PAST_DATE');
  });

  it('rejects an exact duplicate (title + date + venue) with 409', async () => {
    const { token } = await loginDemo();
    const payload = eventPayload();
    expect((await as(token).post('/api/events').send(payload)).status).toBe(201);
    const dup = await as(token).post('/api/events').send({ ...payload, title: payload.title.toUpperCase() });
    expect(dup.status).toBe(409);
    expect(dup.body.code).toBe('DUPLICATE_EVENT');
  });
});

describe('token handling (I-S9, I-S11)', () => {
  it('returns 401 when an optional-auth route gets an invalid bearer token', async () => {
    const res = await api().get('/api/events').set('Authorization', 'Bearer garbage');
    expect(res.status).toBe(401);
    expect(res.body.code).toBe('INVALID_TOKEN');
  });

  it('keeps optional-auth routes anonymous when no header is sent', async () => {
    expect((await api().get('/api/events')).status).toBe(200);
  });

  it('pins HS256: a token signed with another algorithm or alg:none is rejected', () => {
    const hs512 = jwt.sign({ uid: 'usr_001' }, config.jwt.secret, { algorithm: 'HS512' });
    expect(() => verifyToken(hs512)).toThrow();
    const none = jwt.sign({ uid: 'usr_001' }, '', { algorithm: 'none' });
    expect(() => verifyToken(none)).toThrow();
    const ok = signToken({ id: 'usr_001', email: 'a@b.c', name: 'A' });
    expect(jwt.decode(ok, { complete: true }).header.alg).toBe('HS256');
    expect(verifyToken(ok).uid).toBe('usr_001');
  });
});

describe('cors (I-S4)', () => {
  it('socket CORS follows config.corsOrigins', () => {
    const expected = config.corsOrigins.length ? config.corsOrigins : config.isProd ? false : true;
    expect(socketCorsOrigin()).toEqual(expected);
  });
});

describe('chat over Socket.IO (I-S2, I-S8)', () => {
  let server;
  let io;
  let baseUrl;
  let token;
  const sockets = [];

  beforeAll(async () => {
    const { app } = testContext();
    ({ token } = await loginDemo());
    server = http.createServer(app);
    io = attachSocketServer(server);
    await new Promise((resolve) => server.listen(0, resolve));
    baseUrl = `http://127.0.0.1:${server.address().port}`;
  });

  afterAll(async () => {
    for (const s of sockets) s.close();
    await new Promise((resolve) => io.close(resolve));
    await new Promise((resolve) => server.close(resolve));
  });

  async function connect(authToken) {
    const s = ioClient(baseUrl, {
      path: '/socket.io',
      transports: ['websocket'],
      ...(authToken ? { auth: { token: authToken } } : {})
    });
    sockets.push(s);
    await new Promise((resolve) => s.once('connect', resolve));
    return s;
  }
  const emitAck = (s, payload) => new Promise((resolve) => s.emit('chat:message', payload, resolve));
  const heardWithin = (s, event, ms = 400) =>
    new Promise((resolve) => {
      let got = false;
      s.on(event, () => { got = true; });
      setTimeout(() => resolve(got), ms);
    });

  it('rejects an anonymous post with AUTH_REQUIRED and stores nothing', async () => {
    const before = await countChatMessages();
    const s = await connect();
    const ack = await emitAck(s, { text: 'anon' });
    expect(ack.ok).toBe(false);
    expect(ack.error.code).toBe('AUTH_REQUIRED');
    expect(await countChatMessages()).toBe(before);
  });

  it('an anonymous socket may still listen to global chat', async () => {
    const listener = await connect();
    const sender = await connect(token);
    const heard = new Promise((resolve) => listener.once('chat:message', resolve));
    await emitAck(sender, { text: unique('global') });
    expect((await heard).author).not.toBe('Guest');
  });

  it('delivers a room message only to sockets that joined that room (and the sender)', async () => {
    const inRoom = await connect();
    const elsewhere = await connect();
    const sender = await connect(token);
    inRoom.emit('join', ['library']);
    elsewhere.emit('join', ['cafeteria']);
    await new Promise((resolve) => setTimeout(resolve, 150));

    const gotIn = new Promise((resolve) => inRoom.once('chat:message', resolve));
    const leak = heardWithin(elsewhere, 'chat:message');
    const echo = new Promise((resolve) => sender.once('chat:message', resolve));
    const ack = await emitAck(sender, { text: unique('roomonly'), room: 'library' });

    expect(ack.ok).toBe(true);
    expect((await gotIn).room).toBe('library');
    expect((await echo).id).toBe(ack.message.id);
    expect(await leak).toBe(false);
  });

  it('throttles a flooding socket and drops the excess', async () => {
    const s = await connect(token);
    const errors = [];
    s.on('chat:error', (e) => errors.push(e.code));
    const acks = [];
    for (let i = 0; i < CHAT_THROTTLE_MAX + 3; i += 1) {
      acks.push(await emitAck(s, { text: unique('flood') }));
    }
    expect(acks.slice(0, CHAT_THROTTLE_MAX).every((a) => a.ok)).toBe(true);
    expect(acks.slice(CHAT_THROTTLE_MAX).every((a) => !a.ok && a.error.code === 'RATE_LIMITED')).toBe(true);
    expect(errors).toHaveLength(3);
  });

  it('a resend with the same clientId is acked, not duplicated or re-broadcast', async () => {
    const s = await connect(token);
    const listener = await connect();
    const clientId = unique('cid');
    const text = unique('idem');

    const seen = [];
    listener.on('chat:message', (m) => { if (m.text === text) seen.push(m.id); });

    const first = await emitAck(s, { text, clientId });
    expect(first.ok).toBe(true);
    expect(first.duplicate).toBeUndefined();
    const second = await emitAck(s, { text, clientId });
    expect(second.ok).toBe(true);
    expect(second.duplicate).toBe(true);
    expect(second.message.id).toBe(first.message.id);
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(seen).toEqual([first.message.id]);
    const { rows } = await query('SELECT COUNT(*)::int AS n FROM chat_messages WHERE text = $1', [text]);
    expect(rows[0].n).toBe(1);
  });

  it('rejects a malformed clientId', async () => {
    const s = await connect(token);
    const ack = await emitAck(s, { text: 'x', clientId: 123 });
    expect(ack.ok).toBe(false);
    expect(ack.error.code).toBe('BAD_CHAT_MESSAGE');
  });
});
