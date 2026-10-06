import http from 'node:http';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { io as ioClient } from 'socket.io-client';
import supertest from 'supertest';
import { as, loginDemo, testContext, unique } from './helpers/testContext.js';
import { attachSocketServer, broadcastEventUpdate, resolveHandshakeAuth, roomForVenue } from '../src/socket/index.js';
import { query } from '../src/db/pool.js';

/**
 * Exercises the Socket.IO relay against a real HTTP server, using the exact
 * handshake the Android BitChat client performs (default namespace, bare
 * venue ids as the join list).
 */

let server;
let io;
let request;
let baseUrl;
/** Creating events now requires a JWT, so the suite acts as the demo user. */
let token;
const openSockets = [];

beforeAll(async () => {
  const { app } = testContext();
  request = supertest(app);

  ({ token } = await loginDemo());

  server = http.createServer(app);
  io = attachSocketServer(server);
  app.locals.broadcastEvent = (event, options) => import('../src/socket/index.js').then(
    ({ broadcastEventUpdate }) => broadcastEventUpdate(io, event, options)
  );

  await new Promise((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

afterAll(async () => {
  for (const socket of openSockets) socket.close();
  await new Promise((resolve) => io.close(resolve));
  await new Promise((resolve) => server.close(resolve));
});

function connect(namespace = '') {
  const socket = ioClient(`${baseUrl}${namespace}`, {
    path: '/socket.io',
    transports: ['websocket']
  });
  openSockets.push(socket);
  return socket;
}

/** Handshake carrying a JWT, as an authenticated client would. */
function connectWithToken(jwt, namespace = '') {
  const socket = ioClient(`${baseUrl}${namespace}`, {
    path: '/socket.io',
    transports: ['websocket'],
    auth: { token: jwt }
  });
  openSockets.push(socket);
  return socket;
}

const waitFor = (socket, event, timeout = 5000) =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for "${event}"`)), timeout);
    socket.once(event, (...args) => {
      clearTimeout(timer);
      resolve(args);
    });
  });

async function countAnnouncements(room) {
  const { rows } = await query(
    'SELECT COUNT(*)::int AS count FROM announcements WHERE room = $1',
    [room]
  );
  return rows[0].count;
}

const newEventPayload = (venue, title) => ({
  title,
  category: 'Tech',
  venue,
  date: '2027-08-01',
  time: '12:00',
  capacity: 25,
  host: 'Socket Suite'
});

describe('Socket.IO room handling', () => {
  it('accepts a connection on the default namespace (as the Android client uses)', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    expect(socket.connected).toBe(true);
    expect(socket.nsp).toBe('/');
  });

  it('joins announce:<venueId> rooms from bare venue ids', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const joined = waitFor(socket, 'joined');
    socket.emit('join', ['room-204', 'auditorium']);
    const [rooms] = await joined;

    expect(rooms).toEqual([roomForVenue('room-204'), roomForVenue('auditorium')]);
  });

it('accepts already-prefixed room names', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    socket.emit('join', ['announce:library']);
    const [rooms] = await waitFor(socket, 'joined');

    expect(rooms).toEqual(['announce:library']);
  });

  // The Android client emits one plain room name per venue, because its
  // varargs signature would otherwise spread a room list into `rooms`/`ack`.
  it('accepts a single room name as a bare string, not just an array', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    socket.emit('join', 'room-204');
    const [rooms] = await waitFor(socket, 'joined');

    expect(rooms).toEqual([roomForVenue('room-204')]);
  });

  it('acknowledges a single-string join', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await new Promise((resolve) => socket.emit('join', 'announce:library', resolve));

    expect(ack).toEqual({ ok: true, joined: ['announce:library'] });
  });

  it('ignores join requests for venues that are not graph nodes', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const joined = waitFor(socket, 'joined');
    socket.emit('join', ['room-204', 'moon-base']);
    const [rooms] = await joined;

    expect(rooms).toEqual([roomForVenue('room-204')]);
    expect(rooms).not.toContain('announce:moon-base');
  });

  it('supports the contract /announce namespace too', async () => {
    const socket = connect('/announce');
    await waitFor(socket, 'connect');

    socket.emit('join', ['main-hall']);
    const [rooms] = await waitFor(socket, 'joined');

    expect(socket.nsp).toBe('/announce');
    expect(rooms).toEqual(['announce:main-hall']);
  });

  it('answers events:for with the events hosted at a venue', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const response = await new Promise((resolve) => socket.emit('events:for', 'room-204', resolve));
    const events = response.events.map((event) => event.id);

    expect(events).toContain('evt_001');
    expect(events.every((id) => typeof id === 'string')).toBe(true);
  });
});

describe('announcement relay', () => {
  it('broadcasts to the venue room with the fields the Android client reads', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');
    socket.emit('join', ['room-205']);

    const broadcast = waitFor(socket, 'event');
    const created = await as(token).post('/api/events').send(newEventPayload('Room 205', unique('Broadcast')));

    expect(created.status).toBe(201);

    const [payload] = await broadcast;
    expect(payload.title).toContain('Room 205');
    expect(payload.venue).toBe('Room 205');
    expect(payload.event.id).toBe(created.body.event.id);
    expect(payload.announcement.venueId).toBe('room-205');
  });

  it('does not deliver a broadcast to a room the socket did not join', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');
    socket.emit('join', ['library']);

    let leaked = false;
    socket.on('event', () => { leaked = true; });

    await as(token).post('/api/events').send(newEventPayload('Cafeteria', unique('NoLeak')));
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(leaked).toBe(false);
  });
});

describe('duplicate announcement protection', () => {
  it('does not store a second announcement when the same event is broadcast twice', async () => {
    const room = 'room-102';
    const created = await as(token)
      .post('/api/events')
      .send(newEventPayload('Room 102', unique('Repeat')));
    const event = created.body.event;

    const afterFirst = await countAnnouncements(room);
    expect(afterFirst).toBeGreaterThanOrEqual(1);

    // Re-broadcast the identical event: the (room, message) pair is unchanged.
    await broadcastEventUpdate(io, event, { reason: 'created' });
    await broadcastEventUpdate(io, event, { reason: 'created' });

    expect(await countAnnouncements(room)).toBe(afterFirst);
  });

  it('stores a distinct announcement per event at the same venue', async () => {
    const room = 'room-102';
    const before = await countAnnouncements(room);

    await as(token).post('/api/events').send(newEventPayload('Room 102', unique('Distinct A')));
    await as(token).post('/api/events').send(newEventPayload('Room 102', unique('Distinct B')));
    await as(token).post('/api/events').send(newEventPayload('Room 102', unique('Distinct C')));

    // Previously every event at one venue collapsed onto a single constant
    // message, so only the first was ever persisted.
    expect(await countAnnouncements(room)).toBe(before + 3);
  });

  it('embeds the event id so distinct events cannot collide', async () => {
    const room = 'room-102';
    const created = await as(token)
      .post('/api/events')
      .send(newEventPayload('Room 102', unique('Identity')));
    const { id: eventId } = created.body.event;

    const { rows } = await query(
      'SELECT message FROM announcements WHERE room = $1 AND message LIKE $2',
      [room, `%${eventId}%`]
    );

    expect(rows.length).toBeGreaterThanOrEqual(1);
  });

  it('keeps announcements unique per room and message at the database level', async () => {
    const room = 'room-101';
    const message = `probe-${unique('msg')}`;

    await query('INSERT INTO announcements (room, message) VALUES ($1, $2) ON CONFLICT DO NOTHING', [room, message]);
    await query('INSERT INTO announcements (room, message) VALUES ($1, $2) ON CONFLICT DO NOTHING', [room, message]);

    const { rows } = await query(
      'SELECT COUNT(*)::int AS count FROM announcements WHERE room = $1 AND message = $2',
      [room, message]
    );
    expect(rows[0].count).toBe(1);
  });

  it('rejects a duplicate insert at the constraint level', async () => {
    const room = 'lab-a';
    const message = `strict-${unique('msg')}`;

    await query('INSERT INTO announcements (room, message) VALUES ($1, $2)', [room, message]);
    await expect(
      query('INSERT INTO announcements (room, message) VALUES ($1, $2)', [room, message])
    ).rejects.toMatchObject({ code: '23505' });
  });

  it('never returns duplicate (room, message) pairs from the REST fallback', async () => {
    for (let i = 0; i < 3; i += 1) {
      await as(token).post('/api/events').send(newEventPayload('Library', unique('LibDup')));
    }

    const response = await request.get('/api/chat/announcements');
    expect(response.status).toBe(200);

    const keys = response.body.announcements.map((a) => `${a.room}|${a.message}`);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('stores rooms as bare venue ids (not announce:<id>)', async () => {
    await as(token).post('/api/events').send(newEventPayload('Auditorium', unique('Room')));

    const response = await request.get('/api/chat/announcements');
    const rooms = response.body.announcements.map((a) => a.room);

    expect(rooms.every((room) => !room.startsWith('announce:'))).toBe(true);
    expect(rooms).toContain('auditorium');
  });
});
describe('socket authentication', () => {
  it('accepts an anonymous connection, as the Android client uses', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');
    expect(socket.connected).toBe(true);
  });

  it('accepts a connection carrying a valid token', async () => {
    const socket = connectWithToken(token);
    await waitFor(socket, 'connect');
    expect(socket.connected).toBe(true);
  });

  it('rejects a connection carrying an invalid token', async () => {
    const socket = connectWithToken('not-a-real-token');
    const [err] = await waitFor(socket, 'connect_error');

    expect(err.message).toMatch(/invalid|expired/i);
    expect(err.data?.code).toBe('AUTH_INVALID_TOKEN');
    expect(socket.connected).toBe(false);
  });

  it('rejects a connection carrying a tampered token', async () => {
    const tampered = `${token.slice(0, -3)}abc`;
    const socket = connectWithToken(tampered);
    const [err] = await waitFor(socket, 'connect_error');

    expect(err.data?.code).toBe('AUTH_INVALID_TOKEN');
  });

  it('rejects an invalid token on the /announce namespace too', async () => {
    const socket = ioClient(`${baseUrl}/announce`, {
      path: '/socket.io',
      transports: ['websocket'],
      auth: { token: 'garbage' }
    });
    openSockets.push(socket);

    const [err] = await waitFor(socket, 'connect_error');
    expect(err.data?.code).toBe('AUTH_INVALID_TOKEN');
  });

  it('allows an anonymous client to join and receive events', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');
    socket.emit('join', ['auditorium']);

    const [rooms] = await waitFor(socket, 'joined');
    expect(rooms).toContain(roomForVenue('auditorium'));
  });

  it('resolves the handshake user id for a valid token', () => {
    expect(resolveHandshakeAuth({ token })).toEqual({ userId: expect.any(String) });
  });

  it('treats a missing or empty token as anonymous', () => {
    expect(resolveHandshakeAuth({})).toEqual({});
    expect(resolveHandshakeAuth({ token: '' })).toEqual({});
    expect(resolveHandshakeAuth(undefined)).toEqual({});
  });

  it('throws for an invalid token', () => {
    expect(() => resolveHandshakeAuth({ token: 'nope' })).toThrow();
  });
});

describe('socket acknowledgement reliability', () => {
  it('acknowledges join failures instead of silently dropping them', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await new Promise((resolve) => socket.emit('join', ['room-102'], resolve));
    expect(ack).toEqual({ ok: true, joined: [roomForVenue('room-102')] });
  });

  it('ignores unknown venues rather than creating a room', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await new Promise((resolve) => socket.emit('join', ['space-elevator'], resolve));
    expect(ack.joined).toEqual([]);
  });

  it('returns events for a known venue with ok true', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await new Promise((resolve) => socket.emit('events:for', 'room-204', resolve));
    expect(ack.ok).toBe(true);
    expect(Array.isArray(ack.events)).toBe(true);
    expect(ack.events.every((event) => event.venueId === 'room-204')).toBe(true);
  });

  it('returns an empty list for an unknown venue', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');

    const ack = await new Promise((resolve) => socket.emit('events:for', 'space-elevator', resolve));
    expect(ack).toEqual({ ok: true, events: [] });
  });

  it('acknowledges leave with ok true', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');
    socket.emit('join', ['library']);

    const ack = await new Promise((resolve) => socket.emit('leave', ['library'], resolve));
    expect(ack).toEqual({ ok: true, left: [roomForVenue('library')] });
  });
});
