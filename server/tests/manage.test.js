import http from 'node:http';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { io as ioClient } from 'socket.io-client';
import { as, api, testContext, unique } from './helpers/testContext.js';
import { query } from '../src/db/pool.js';
import { insertUser, setUserRole } from '../src/models/userModel.js';
import { signToken } from '../src/auth/jwt.js';
import { newId } from '../src/utils/id.js';
import { attachSocketServer, bindSocketBroadcasts } from '../src/socket/index.js';
import { resetAnnouncementThrottle } from '../src/services/managerService.js';
import { refreshRegistry } from '../src/navigation/registry.js';

/**
 * Event-manager API (/api/manage/*): role gates, ownership, venue + edge CRUD
 * against the DB-backed graph, `map:updated`, and announcement broadcast.
 *
 * The suite mutates the shared campus map, so every venue/edge it creates is
 * removed (and every seed edge it touches restored) in afterAll.
 */

const created = { users: [], venues: [], events: [] };
let server;
let io;
let baseUrl;
const openSockets = [];
let orgA;
let orgB;
let admin;
let student;

async function makeUser(role) {
  const id = newId('usr');
  await insertUser({ id, name: `Mgr ${role}`, email: `${unique('mgr')}@x.io`, passwordHash: 'x' });
  await setUserRole(id, role);
  created.users.push(id);
  return { id, role, token: signToken({ id, email: 'x@x.io', name: 'x' }) };
}

const waitFor = (socket, event, timeout = 5000) =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for "${event}"`)), timeout);
    socket.once(event, (...args) => {
      clearTimeout(timer);
      resolve(args);
    });
  });

const noEvent = (socket, event, ms = 400) =>
  new Promise((resolve, reject) => {
    const handler = () => reject(new Error(`unexpected "${event}"`));
    socket.once(event, handler);
    setTimeout(() => {
      socket.off(event, handler);
      resolve();
    }, ms);
  });

function connect(namespace = '') {
  const socket = ioClient(`${baseUrl}${namespace}`, { path: '/socket.io', transports: ['websocket'] });
  openSockets.push(socket);
  return socket;
}

const slug = () => unique('t').toLowerCase().replace(/[^a-z0-9-]/g, '');

async function newVenue(token, body = {}) {
  const id = body.id ?? slug();
  const res = await as(token).post('/api/manage/venues').send({
    id,
    name: body.name ?? `Venue ${id}`,
    x: body.x ?? 0.5,
    y: body.y ?? 0.5
  });
  if (res.status === 201) created.venues.push(id);
  return res;
}

const eventBody = (venue, title) => ({
  title,
  category: 'Tech',
  venue,
  date: '2027-09-01',
  time: '10:00',
  capacity: 10
});

beforeAll(async () => {
  const { app } = testContext();
  orgA = await makeUser('organizer');
  orgB = await makeUser('organizer');
  admin = await makeUser('admin');
  student = await makeUser('student');
  resetAnnouncementThrottle();

  server = http.createServer(app);
  io = attachSocketServer(server);
  bindSocketBroadcasts(app, io);
  await new Promise((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

afterAll(async () => {
  for (const socket of openSockets) socket.close();
  await new Promise((resolve) => io.close(resolve));
  await new Promise((resolve) => server.close(resolve));

  await query('DELETE FROM announcements WHERE sent_by = ANY($1::text[])', [created.users]);
  await query('DELETE FROM events WHERE created_by = ANY($1::text[])', [created.users]);
  await query('DELETE FROM venues WHERE id = ANY($1::text[])', [created.venues]);
  await query('DELETE FROM users WHERE id = ANY($1::text[])', [created.users]);
  // Seed edges removed/changed by the suite are restored below by the tests
  // themselves; re-sync the cache from the DB regardless.
  await refreshRegistry();
});

describe('role gate', () => {
  const routes = [
    ['get', '/api/manage/overview'],
    ['get', '/api/manage/events'],
    ['post', '/api/manage/venues'],
    ['put', '/api/manage/venues/lobby'],
    ['delete', '/api/manage/venues/lobby'],
    ['post', '/api/manage/edges'],
    ['put', '/api/manage/edges'],
    ['delete', '/api/manage/edges?from=a&to=b'],
    ['get', '/api/manage/announcements'],
    ['post', '/api/manage/announcements'],
    ['delete', '/api/manage/announcements/1']
  ];

  it.each(routes)('%s %s -> 401 without a token', async (method, url) => {
    const res = await api()[method](url).send({});
    expect(res.status).toBe(401);
  });

  it.each(routes)('%s %s -> 403 FORBIDDEN for a student', async (method, url) => {
    const res = await as(student.token)[method](url).send({});
    expect(res.status).toBe(403);
    expect(res.body.code).toBe('FORBIDDEN');
  });
});

describe('GET /api/manage/overview + /events', () => {
  it('counts only my events for an organizer and everything for an admin', async () => {
    const title = unique('Ov');
    const made = await as(orgA.token).post('/api/events').send(eventBody('Room 101', title));
    expect(made.status).toBe(201);
    created.events.push(made.body.event.id);
    await query('INSERT INTO bookmarks (user_id, event_id) VALUES ($1, $2)', [student.id, made.body.event.id]);

    const mine = await as(orgA.token).get('/api/manage/overview');
    expect(mine.status).toBe(200);
    expect(mine.body.events).toEqual({ total: 1, upcoming: 1, past: 0 });
    expect(mine.body.bookmarks).toBe(1);
    expect(mine.body.announcements).toBe(0);
    expect(mine.body.venues).toBeGreaterThanOrEqual(14);
    expect(mine.body.edges).toBeGreaterThanOrEqual(19);

    const other = await as(orgB.token).get('/api/manage/overview');
    expect(other.body.events.total).toBe(0);

    const all = await as(admin.token).get('/api/manage/overview');
    expect(all.body.events.total).toBeGreaterThanOrEqual(1);
  });

  it('lists my events with createdBy and bookmarkCount, sorted by date', async () => {
    const res = await as(orgA.token).get('/api/manage/events');
    expect(res.status).toBe(200);
    expect(res.body.events).toHaveLength(1);
    const [event] = res.body.events;
    expect(event.createdBy).toBe(orgA.id);
    expect(event.bookmarkCount).toBe(1);
    expect(event.venueId).toBe('room-101');
    const dates = res.body.events.map((e) => e.date);
    expect(dates).toEqual([...dates].sort());
  });

  it('scope=all is admin only and includes seed events', async () => {
    expect((await as(orgA.token).get('/api/manage/events?scope=all')).status).toBe(403);
    const res = await as(admin.token).get('/api/manage/events?scope=all');
    expect(res.status).toBe(200);
    expect(res.body.events.length).toBeGreaterThan(1);
    expect(res.body.events.some((e) => e.createdBy === null)).toBe(true);
    expect((await as(orgA.token).get('/api/manage/events?scope=nope')).status).toBe(400);
  });

  it('organizer ownership vs admin on PUT/DELETE /api/events/:id', async () => {
    const eventId = created.events[0];
    const denied = await as(orgB.token).put(`/api/events/${eventId}`).send({ title: 'Hijack' });
    expect(denied.status).toBe(403);
    const adminEdit = await as(admin.token).put(`/api/events/${eventId}`).send({ capacity: 11 });
    expect(adminEdit.status).toBe(200);
    expect((await as(orgB.token).delete(`/api/events/${eventId}`)).status).toBe(403);
  });
});

describe('venues', () => {
  it('creates, renames, moves and serves a venue on /navigation/map', async () => {
    const id = slug();
    const res = await newVenue(orgA.token, { id, name: `Annex ${id}`, x: 0.1, y: 0.9 });
    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({ id, name: `Annex ${id}`, x: 0.1, y: 0.9, geoPlaced: false, kind: "", description: "", edge: null });

    const map = await api().get('/api/navigation/map');
    const node = map.body.venues.find((v) => v.id === id);
    expect(node).toMatchObject({ id, name: `Annex ${id}`, x: 0.1, y: 0.9, neighbors: [] });

    const put = await as(orgB.token).put(`/api/manage/venues/${id}`).send({ name: `Renamed ${id}`, x: 0.2 });
    expect(put.status).toBe(200);
    expect(put.body).toMatchObject({ id, name: `Renamed ${id}`, x: 0.2, y: 0.9 });

    const names = await api().get('/api/navigation/venues');
    expect(names.body.venues.find((v) => v.id === id).name).toBe(`Renamed ${id}`);
  });

  it('validates input (400) and rejects duplicates (409 DUPLICATE_VENUE)', async () => {
    const bad = [
      { id: 'Bad Id', name: 'x', x: 0.5, y: 0.5 },
      { id: 'a--b', name: 'x', x: 0.5, y: 0.5 },
      { id: 'all', name: 'x', x: 0.5, y: 0.5 },
      { id: 'x'.repeat(41), name: 'x', x: 0.5, y: 0.5 },
      { id: 'ok-id', name: '', x: 0.5, y: 0.5 },
      { id: 'ok-id', name: 'y'.repeat(61), x: 0.5, y: 0.5 },
      { id: 'ok-id', name: 'n', x: 1.5, y: 0.5 },
      { id: 'ok-id', name: 'n', x: 0.5, y: -0.1 },
      { id: 'ok-id', name: 'n', x: '0.5', y: 0.5 },
      { id: 'ok-id', name: 'n', x: 0.5 }
    ];
    for (const body of bad) {
      const res = await as(orgA.token).post('/api/manage/venues').send(body);
      expect(res.status, JSON.stringify(body)).toBe(400);
      expect(res.body.code).toBe('VALIDATION_ERROR');
    }

    const dupId = await newVenue(orgA.token, { id: 'lobby', name: 'Another Lobby' });
    expect(dupId.status).toBe(409);
    expect(dupId.body.code).toBe('DUPLICATE_VENUE');

    const dupName = await newVenue(orgA.token, { name: 'room 204' });
    expect(dupName.status).toBe(409);
    expect(dupName.body.code).toBe('DUPLICATE_VENUE');

    const rename = await as(orgA.token).put('/api/manage/venues/lobby').send({ name: 'Main Hall' });
    expect(rename.status).toBe(409);
  });

  it('PUT validates the body and 404s unknown ids; id is immutable', async () => {
    expect((await as(orgA.token).put('/api/manage/venues/nowhere').send({ x: 0.1 })).status).toBe(404);
    const unknown = await as(orgA.token).put('/api/manage/venues/nowhere').send({ x: 0.1 });
    expect(unknown.body.code).toBe('UNKNOWN_VENUE');
    expect((await as(orgA.token).put('/api/manage/venues/lobby').send({})).status).toBe(400);
    expect((await as(orgA.token).put('/api/manage/venues/lobby').send({ id: 'other' })).status).toBe(400);
    expect((await as(orgA.token).put('/api/manage/venues/lobby').send({ x: 2 })).status).toBe(400);
  });

  it('DELETE -> 200, 404 unknown, 409 VENUE_IN_USE when an event is held there', async () => {
    const id = slug();
    const name = `Hall ${id}`;
    await newVenue(orgA.token, { id, name });

    const event = await as(orgA.token).post('/api/events').send(eventBody(name, unique('InUse')));
    expect(event.status).toBe(201);
    created.events.push(event.body.event.id);

    const blocked = await as(orgA.token).delete(`/api/manage/venues/${id}`);
    expect(blocked.status).toBe(409);
    expect(blocked.body.code).toBe('VENUE_IN_USE');

    await as(orgA.token).delete(`/api/events/${event.body.event.id}`);
    const ok = await as(orgA.token).delete(`/api/manage/venues/${id}`);
    expect(ok.status).toBe(200);
    expect(ok.body).toEqual({ deleted: true, id });

    const gone = await as(orgA.token).delete(`/api/manage/venues/${id}`);
    expect(gone.status).toBe(404);
    expect(gone.body.code).toBe('UNKNOWN_VENUE');

    const map = await api().get('/api/navigation/map');
    expect(map.body.venues.some((v) => v.id === id)).toBe(false);
  });

  it('events can be created at a new venue by name and by id, and are routable to it', async () => {
    const id = slug();
    const name = `Pavilion ${id}`;
    await newVenue(orgA.token, { id, name });
    await as(orgA.token).post('/api/manage/edges').send({ from: 'lobby', to: id, distanceM: 12 });

    const byName = await as(orgA.token).post('/api/events').send(eventBody(name, unique('NewVenue')));
    expect(byName.status).toBe(201);
    expect(byName.body.event.venueId).toBe(id);
    created.events.push(byName.body.event.id);

    const route = await api().get(`/api/events/${byName.body.event.id}/route?from=entrance`);
    expect(route.status).toBe(200);
    expect(route.body.nodes.at(-1).id).toBe(id);
    expect(route.body.totalDistanceM).toBe(52);
  });
});

describe('edges', () => {
  it('CRUD changes /navigation/path and /navigation/map', async () => {
    // Baseline: entrance -> room-204 is 115 m via main-hall / room-102 / tech-lounge.
    const before = await api().post('/api/navigation/path').send({ from: 'entrance', to: 'room-204' });
    expect(before.body.totalDistanceM).toBe(115);

    const add = await as(orgA.token).post('/api/manage/edges').send({ from: 'room-204', to: 'entrance', distanceM: 20 });
    expect(add.status).toBe(201);
    // Stored once, ordered from < to.
    expect(add.body).toEqual({ from: 'entrance', to: 'room-204', distanceM: 20 });

    try {
      const after = await api().post('/api/navigation/path').send({ from: 'Entrance', to: 'Room 204' });
      expect(after.body.totalDistanceM).toBe(20);
      expect(after.body.nodes.map((n) => n.id)).toEqual(['entrance', 'room-204']);

      const map = await api().get('/api/navigation/map');
      expect(map.body.edges).toContainEqual({ from: 'entrance', to: 'room-204', distanceM: 20 });
      expect(map.body.venues.find((v) => v.id === 'entrance').neighbors).toContain('room-204');

      const dup = await as(orgA.token).post('/api/manage/edges').send({ from: 'entrance', to: 'room-204', distanceM: 5 });
      expect(dup.status).toBe(409);
      expect(dup.body.code).toBe('DUPLICATE_EDGE');

      const upd = await as(orgB.token).put('/api/manage/edges').send({ from: 'room-204', to: 'entrance', distanceM: 300 });
      expect(upd.status).toBe(200);
      expect(upd.body.distanceM).toBe(300);
      const longer = await api().post('/api/navigation/path').send({ from: 'entrance', to: 'room-204' });
      expect(longer.body.totalDistanceM).toBe(115);
    } finally {
      const del = await as(orgA.token).delete('/api/manage/edges?from=room-204&to=entrance');
      expect(del.status).toBe(200);
      expect(del.body).toEqual({ deleted: true });
    }

    const restored = await api().post('/api/navigation/path').send({ from: 'entrance', to: 'room-204' });
    expect(restored.body.totalDistanceM).toBe(115);
    const again = await as(orgA.token).delete('/api/manage/edges?from=room-204&to=entrance');
    expect(again.status).toBe(404);
    expect(again.body.code).toBe('UNKNOWN_EDGE');
  });

  it('deleting a seed edge reroutes, and restoring it brings the route back', async () => {
    const del = await as(admin.token).delete('/api/manage/edges?from=lobby&to=entrance');
    expect(del.status).toBe(200);
    try {
      const res = await api().post('/api/navigation/path').send({ from: 'entrance', to: 'lobby' });
      expect(res.status).toBe(400);
      expect(res.body.code).toBe('NO_PATH');
    } finally {
      const back = await as(admin.token).post('/api/manage/edges').send({ from: 'entrance', to: 'lobby', distanceM: 40 });
      expect(back.status).toBe(201);
    }
    const ok = await api().post('/api/navigation/path').send({ from: 'entrance', to: 'lobby' });
    expect(ok.body.totalDistanceM).toBe(40);
  });

  it('a venue with no edges is allowed and unreachable (NO_PATH)', async () => {
    const res = await newVenue(orgA.token, {});
    const path = await api().post('/api/navigation/path').send({ from: 'entrance', to: res.body.id });
    expect(path.status).toBe(400);
    expect(path.body.code).toBe('NO_PATH');
  });

  it('validates edges: 400 for bad distance / self-loop / unknown venue, 404 unknown edge', async () => {
    const post = (body) => as(orgA.token).post('/api/manage/edges').send(body);
    for (const distanceM of [0, -3, 1001, 2.5, '10']) {
      const res = await post({ from: 'lobby', to: 'library', distanceM });
      expect(res.status, String(distanceM)).toBe(400);
    }
    expect((await post({ from: 'lobby', to: 'lobby', distanceM: 5 })).status).toBe(400);
    expect((await post({ from: 'lobby' })).status).toBe(400);

    const unknown = await post({ from: 'lobby', to: 'atlantis', distanceM: 5 });
    expect(unknown.status).toBe(400);
    expect(unknown.body.code).toBe('UNKNOWN_VENUE');

    const put = await as(orgA.token).put('/api/manage/edges').send({ from: 'lobby', to: 'library', distanceM: 5 });
    expect(put.status).toBe(404);
    expect(put.body.code).toBe('UNKNOWN_EDGE');

    expect((await as(orgA.token).delete('/api/manage/edges?from=lobby')).status).toBe(400);
  });

  it('deleting a venue cascades its edges', async () => {
    const baseline = (await api().get('/api/navigation/map')).body.edges.length;
    const res = await newVenue(orgA.token, {});
    await as(orgA.token).post('/api/manage/edges').send({ from: 'lobby', to: res.body.id, distanceM: 9 });
    expect((await api().get('/api/navigation/map')).body.edges).toHaveLength(baseline + 1);
    await as(orgA.token).delete(`/api/manage/venues/${res.body.id}`);
    const map = await api().get('/api/navigation/map');
    expect(map.body.edges.some((e) => e.from === res.body.id || e.to === res.body.id)).toBe(false);
    expect(map.body.edges).toHaveLength(baseline);
  });
});

describe('map:updated', () => {
  it('is emitted to default and /announce namespace clients after every write', async () => {
    const a = connect();
    const b = connect('/announce');
    await Promise.all([waitFor(a, 'connect'), waitFor(b, 'connect')]);

    const gotA = waitFor(a, 'map:updated');
    const gotB = waitFor(b, 'map:updated');
    const res = await newVenue(orgA.token, {});
    expect(res.status).toBe(201);
    const [payloadA] = await gotA;
    const [payloadB] = await gotB;
    expect(Number.isNaN(Date.parse(payloadA.at))).toBe(false);
    expect(Number.isNaN(Date.parse(payloadB.at))).toBe(false);

    const second = waitFor(a, 'map:updated');
    await as(orgA.token).put(`/api/manage/venues/${res.body.id}`).send({ x: 0.3 });
    await second;
  });

  it('is not emitted when a write fails', async () => {
    const a = connect();
    await waitFor(a, 'connect');
    const silent = noEvent(a, 'map:updated');
    await as(orgA.token).post('/api/manage/venues').send({ id: 'lobby', name: 'Nope', x: 0.1, y: 0.1 });
    await silent;
  });
});

describe('announcements', () => {
  it('venue announcement: 201, stored, reaches a client joined to that room only', async () => {
    resetAnnouncementThrottle();
    const inRoom = connect();
    const elsewhere = connect();
    await Promise.all([waitFor(inRoom, 'connect'), waitFor(elsewhere, 'connect')]);
    inRoom.emit('join', ['room-205']);
    elsewhere.emit('join', ['library']);
    await Promise.all([waitFor(inRoom, 'joined'), waitFor(elsewhere, 'joined')]);

    const message = unique('Venue notice');
    const heard = waitFor(inRoom, 'event');
    const silent = noEvent(elsewhere, 'event');
    const res = await as(orgA.token).post('/api/manage/announcements').send({ message, venueId: 'room-205' });

    expect(res.status).toBe(201);
    expect(res.body.announcement).toMatchObject({ room: 'room-205', message, eventId: null, sentBy: orgA.id });
    expect(typeof res.body.announcement.id).toBe('number');
    expect(Number.isNaN(Date.parse(res.body.announcement.at))).toBe(false);

    const [payload] = await heard;
    // Same fields the Android ChatViewModel reads off the top level.
    expect(payload.title).toBe(message);
    expect(payload.venue).toBe('Room 205');
    expect(payload.venueId).toBe('room-205');
    expect(payload.reason).toBe('announcement');
    expect(payload.announcement.message).toBe(message);
    await silent;
  });

  it('global announcement reaches a client that joined no venue room', async () => {
    resetAnnouncementThrottle();
    const nobody = connect();
    const nsClient = connect('/announce');
    await Promise.all([waitFor(nobody, 'connect'), waitFor(nsClient, 'connect')]);

    const message = unique('Campus notice');
    const heard = waitFor(nobody, 'event');
    const heardNs = waitFor(nsClient, 'event');
    const res = await as(admin.token).post('/api/manage/announcements').send({ message });
    expect(res.status).toBe(201);
    expect(res.body.announcement.room).toBe('all');

    const [payload] = await heard;
    expect(payload.title).toBe(message);
    expect(payload.venue).toBe('All venues');
    expect(payload.global).toBe(true);
    const [payloadNs] = await heardNs;
    expect(payloadNs.title).toBe(message);
  });

  it('eventId targets the event venue room, stores event_id, and enforces ownership', async () => {
    resetAnnouncementThrottle();
    const made = await as(orgA.token).post('/api/events').send(eventBody('Library', unique('AnnEvt')));
    created.events.push(made.body.event.id);
    const eventId = made.body.event.id;

    const socket = connect();
    await waitFor(socket, 'connect');
    socket.emit('join', ['library']);
    await waitFor(socket, 'joined');
    // The event-created relay arrives first; ignore it.
    await new Promise((r) => setTimeout(r, 300));

    const message = unique('Doors open');
    const heard = new Promise((resolve) => {
      socket.on('event', (p) => p.reason === 'announcement' && resolve(p));
    });
    const res = await as(orgA.token).post('/api/manage/announcements').send({ message, eventId });
    expect(res.status).toBe(201);
    expect(res.body.announcement).toMatchObject({ room: 'library', eventId, sentBy: orgA.id });
    const payload = await heard;
    expect(payload.eventId).toBe(eventId);
    expect(payload.event.id).toBe(eventId);
    expect(payload.venue).toBe('Library');

    const stored = await api().get(`/api/chat/announcements?eventId=${eventId}`);
    expect(stored.body.announcements.map((a) => a.message)).toContain(message);

    const denied = await as(orgB.token).post('/api/manage/announcements').send({ message: unique('x'), eventId });
    expect(denied.status).toBe(403);
    const adminOk = await as(admin.token).post('/api/manage/announcements').send({ message: unique('x'), eventId });
    expect(adminOk.status).toBe(201);
    const missing = await as(orgA.token).post('/api/manage/announcements').send({ message: 'x', eventId: 'evt_nope' });
    expect(missing.status).toBe(404);
    const mismatch = await as(orgA.token)
      .post('/api/manage/announcements')
      .send({ message: unique('x'), eventId, venueId: 'lobby' });
    expect(mismatch.status).toBe(400);
  });

  it('validates the body (400) and rejects an unknown venue (repeat text is allowed)', async () => {
    resetAnnouncementThrottle();
    const post = (body) => as(orgA.token).post('/api/manage/announcements').send(body);
    expect((await post({})).status).toBe(400);
    expect((await post({ message: '' })).status).toBe(400);
    expect((await post({ message: 'a'.repeat(281) })).status).toBe(400);
    expect((await post({ message: 'ok', extra: 1 })).status).toBe(400);
    const unknown = await post({ message: 'ok', venueId: 'atlantis' });
    expect(unknown.status).toBe(400);
    expect(unknown.body.code).toBe('UNKNOWN_VENUE');

    const message = unique('Dup');
    expect((await post({ message, venueId: 'lobby' })).status).toBe(201);
    // 1.5.0: managers may repeat the same text to the same room.
    expect((await post({ message, venueId: 'lobby' })).status).toBe(201);
    expect((await post({ message, venueId: 'Lobby' })).status).toBe(201);
    expect((await post({ message: 'a'.repeat(280) })).status).toBe(201);
  });

  it('lists newest first: mine for everyone, scope=all admin only', async () => {
    resetAnnouncementThrottle();
    const one = unique('First');
    const two = unique('Second');
    await as(orgB.token).post('/api/manage/announcements').send({ message: one });
    await as(orgB.token).post('/api/manage/announcements').send({ message: two });

    const mine = await as(orgB.token).get('/api/manage/announcements');
    expect(mine.status).toBe(200);
    const messages = mine.body.announcements.map((a) => a.message);
    expect(messages.indexOf(two)).toBeLessThan(messages.indexOf(one));
    expect(mine.body.announcements.every((a) => a.sentBy === orgB.id)).toBe(true);

    expect((await as(orgB.token).get('/api/manage/announcements?scope=all')).status).toBe(403);
    const all = await as(admin.token).get('/api/manage/announcements?scope=all');
    expect(all.status).toBe(200);
    expect(all.body.announcements.some((a) => a.sentBy === null)).toBe(true);
    expect(all.body.announcements.some((a) => a.sentBy === orgB.id)).toBe(true);
  });

  it('DELETE: own or admin; 403 for another organizer; 404 unknown', async () => {
    resetAnnouncementThrottle();
    const made = await as(orgA.token).post('/api/manage/announcements').send({ message: unique('Del') });
    const id = made.body.announcement.id;

    expect((await as(orgB.token).delete(`/api/manage/announcements/${id}`)).status).toBe(403);
    const own = await as(orgA.token).delete(`/api/manage/announcements/${id}`);
    expect(own.status).toBe(200);
    expect(own.body).toEqual({ deleted: true, id });
    const gone = await as(orgA.token).delete(`/api/manage/announcements/${id}`);
    expect(gone.status).toBe(404);
    expect(gone.body.code).toBe('ANNOUNCEMENT_NOT_FOUND');

    const second = await as(orgA.token).post('/api/manage/announcements').send({ message: unique('Del') });
    const byAdmin = await as(admin.token).delete(`/api/manage/announcements/${second.body.announcement.id}`);
    expect(byAdmin.status).toBe(200);

    expect((await as(orgA.token).delete('/api/manage/announcements/abc')).status).toBe(400);
  });

  it('throttles to 10 per minute per user with 429 RATE_LIMITED', async () => {
    resetAnnouncementThrottle();
    const limited = await makeUser('organizer');
    const send = () =>
      as(limited.token).post('/api/manage/announcements').send({ message: unique('Burst') });
    for (let i = 0; i < 10; i += 1) {
      expect((await send()).status).toBe(201);
    }
    const blocked = await send();
    expect(blocked.status).toBe(429);
    expect(blocked.body.code).toBe('RATE_LIMITED');
    // Another user is unaffected.
    expect((await as(orgA.token).post('/api/manage/announcements').send({ message: unique('Ok') })).status).toBe(201);
  });
});

describe('event auto-announcement wording', () => {
  it('uses "New event: <title> at <venue>", "Updated: <title>", "Cancelled: <title>"', async () => {
    const socket = connect();
    await waitFor(socket, 'connect');
    socket.emit('join', ['room-102']);
    await waitFor(socket, 'joined');

    const title = unique('Wording');
    const next = () => waitFor(socket, 'event');

    let heard = next();
    const made = await as(orgA.token).post('/api/events').send(eventBody('Room 102', title));
    expect(made.status).toBe(201);
    const [createdPayload] = await heard;
    expect(createdPayload.title).toBe(`New event: ${title} at Room 102`);
    expect(createdPayload.reason).toBe('created');
    const { rows } = await query('SELECT message FROM announcements WHERE event_id = $1', [made.body.event.id]);
    expect(rows[0].message).toBe(`New event: ${title} at Room 102 [${made.body.event.id}]`);

    heard = next();
    await as(orgA.token).put(`/api/events/${made.body.event.id}`).send({ capacity: 12 });
    expect((await heard)[0].title).toBe(`Updated: ${title}`);

    heard = next();
    await as(orgA.token).delete(`/api/events/${made.body.event.id}`);
    expect((await heard)[0].title).toBe(`Cancelled: ${title}`);
  });
});
