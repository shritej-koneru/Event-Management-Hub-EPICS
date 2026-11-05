import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { as, api, testContext, unique } from './helpers/testContext.js';
import { query } from '../src/db/pool.js';
import { insertUser, setUserRole } from '../src/models/userModel.js';
import { recordAnnouncement } from '../src/models/announcementModel.js';
import { signToken } from '../src/auth/jwt.js';
import { newId } from '../src/utils/id.js';
import { resetAnnouncementThrottle } from '../src/services/managerService.js';
import { refreshRegistry } from '../src/navigation/registry.js';
import { CAMPUS_BOUNDS, haversineMeters, latLngToXY, xyToLatLng } from '../src/navigation/geo.js';

/**
 * Contract 1.5.0: campus bounds + lat/lng venues, repeatable manager
 * announcements, and admin-only account management.
 */

const created = { users: [], venues: [] };
let admin;
let orgA;
let student;

async function makeUser(role) {
  const id = newId('usr');
  await insertUser({ id, name: `Geo ${role}`, email: `${unique('geo')}@x.io`, passwordHash: 'x' });
  await setUserRole(id, role);
  created.users.push(id);
  return { id, role, token: signToken({ id, email: 'x@x.io', name: 'x' }) };
}

const slug = () => unique('g').toLowerCase().replace(/[^a-z0-9-]/g, '');

async function postVenue(token, body) {
  const res = await as(token).post('/api/manage/venues').send(body);
  if (res.status === 201) created.venues.push(body.id);
  return res;
}

const fetchMap = async () => (await api().get('/api/navigation/map')).body;

beforeAll(async () => {
  testContext();
  admin = await makeUser('admin');
  orgA = await makeUser('organizer');
  student = await makeUser('student');
  resetAnnouncementThrottle();
});

afterAll(async () => {
  await query('DELETE FROM announcements WHERE sent_by = ANY($1::text[])', [created.users]);
  await query(`DELETE FROM announcements WHERE message LIKE 'geo-auto-%'`);
  await query('DELETE FROM venues WHERE id = ANY($1::text[])', [created.venues]);
  await query('DELETE FROM users WHERE id = ANY($1::text[]) OR email LIKE $2', [created.users, '%@geo-made.io']);
  await refreshRegistry();
});

describe('GET /api/navigation/map geo fields', () => {
  it('returns bounds and per-venue lat/lng derived from x/y for seeded venues', async () => {
    const map = await fetchMap();
    expect(map.bounds).toMatchObject({
      north: CAMPUS_BOUNDS.north,
      south: CAMPUS_BOUNDS.south,
      west: CAMPUS_BOUNDS.west,
      east: CAMPUS_BOUNDS.east
    });
    expect(map.bounds.center.lat).toBeCloseTo(16.485277, 6);
    expect(map.bounds.center.lng).toBeCloseTo(80.69278, 6);

    const lobby = map.venues.find((v) => v.id === 'lobby');
    const expected = xyToLatLng(lobby.x, lobby.y);
    expect(lobby.lat).toBeCloseTo(expected.lat, 9);
    expect(lobby.lng).toBeCloseTo(expected.lng, 9);
    expect(lobby).toMatchObject({ geoPlaced: false, kind: '', description: '' });
    expect(Array.isArray(lobby.neighbors)).toBe(true);
    for (const v of map.venues) {
      expect(Number.isFinite(v.lat)).toBe(true);
      expect(Number.isFinite(v.lng)).toBe(true);
    }
  });

  it('GET /navigation/venues carries the same additive fields', async () => {
    const { body } = await api().get('/api/navigation/venues');
    const lobby = body.venues.find((v) => v.id === 'lobby');
    expect(lobby).toMatchObject({ geoPlaced: false, kind: '', description: '' });
    expect(Number.isFinite(lobby.lat)).toBe(true);
    expect(Array.isArray(lobby.neighbors)).toBe(true);
  });
});

describe('POST /api/manage/venues by lat/lng', () => {
  it('computes x/y from the bounds, stores lat/lng and geoPlaced=true', async () => {
    const id = slug();
    const lat = 16.4855;
    const lng = 80.6921;
    const res = await postVenue(orgA.token, {
      id,
      name: `Geo ${id}`,
      lat,
      lng,
      kind: 'Lab',
      description: 'A placed lab'
    });
    expect(res.status).toBe(201);
    const xy = latLngToXY(lat, lng);
    expect(res.body.x).toBeCloseTo(xy.x, 9);
    expect(res.body.y).toBeCloseTo(xy.y, 9);
    expect(res.body).toMatchObject({ id, lat, lng, kind: 'Lab', description: 'A placed lab', geoPlaced: true, edge: null });

    const node = (await fetchMap()).venues.find((v) => v.id === id);
    expect(node).toMatchObject({ lat, lng, kind: 'Lab', geoPlaced: true, neighbors: [] });
  });

  it('400 OUTSIDE_CAMPUS outside the bounds; nothing is created', async () => {
    const id = slug();
    const res = await postVenue(orgA.token, { id, name: `Far ${id}`, lat: 17.0, lng: 80.69 });
    expect(res.status).toBe(400);
    expect(res.body.code).toBe('OUTSIDE_CAMPUS');
    const east = await postVenue(orgA.token, { id, name: `Far ${id}`, lat: 16.4855, lng: 80.7 });
    expect(east.body.code).toBe('OUTSIDE_CAMPUS');
    expect((await fetchMap()).venues.some((v) => v.id === id)).toBe(false);
  });

  it('rejects mixed or half pairs and a missing position (400)', async () => {
    const id = slug();
    const base = { id, name: `Bad ${id}` };
    for (const body of [
      { ...base, x: 0.5, y: 0.5, lat: 16.4855, lng: 80.692 },
      { ...base, lat: 16.4855 },
      { ...base, lng: 80.692 },
      { ...base, x: 0.5 },
      base,
      { ...base, x: 0.5, y: 0.5, connectToNearest: true },
      { ...base, lat: 16.4855, lng: 80.692, connectToNearest: 'yes' },
      { ...base, lat: 16.4855, lng: 80.692, description: 'd'.repeat(501) }
    ]) {
      const res = await postVenue(orgA.token, body);
      expect(res.status, JSON.stringify(body)).toBe(400);
    }
  });

  it('connectToNearest links to the nearest venue with the haversine distance and is routable', async () => {
    const map = await fetchMap();
    const target = map.venues.find((v) => v.id === 'library') ?? map.venues[0];
    // A few metres off the target's effective position, still inside bounds.
    const lat = Math.min(CAMPUS_BOUNDS.north, target.lat + 0.00005);
    const lng = target.lng;

    let best = null;
    for (const v of map.venues) {
      const d = haversineMeters(lat, lng, v.lat, v.lng);
      if (!best || d < best.d) best = { id: v.id, d };
    }
    expect(best.id).toBe(target.id);

    const id = slug();
    const res = await postVenue(orgA.token, { id, name: `Near ${id}`, lat, lng, connectToNearest: true });
    expect(res.status).toBe(201);
    expect([res.body.edge.from, res.body.edge.to].sort()).toEqual([id, best.id].sort());
    expect(res.body.edge.distanceM).toBe(Math.min(1000, Math.max(1, Math.round(best.d))));

    const after = await fetchMap();
    expect(after.edges).toContainEqual(res.body.edge);
    expect(after.venues.find((v) => v.id === id).neighbors).toEqual([best.id]);

    const path = await api().post('/api/navigation/path').send({ from: 'entrance', to: id });
    expect(path.status).toBe(200);
    expect(path.body.nodes.at(-1).id).toBe(id);
    expect(path.body.nodes.map((n) => n.id)).toContain(best.id);
  });

  it('without connectToNearest the venue is isolated (edge null, no path)', async () => {
    const id = slug();
    const res = await postVenue(orgA.token, { id, name: `Iso ${id}`, lat: 16.4858, lng: 80.6932 });
    expect(res.status).toBe(201);
    expect(res.body.edge).toBeNull();
    const path = await api().post('/api/navigation/path').send({ from: 'entrance', to: id });
    expect(path.status).toBeGreaterThanOrEqual(400);
  });
});

describe('PUT /api/manage/venues/:id positions', () => {
  it('lat/lng recomputes x/y and sets geoPlaced; x/y clears the stored lat/lng', async () => {
    const id = slug();
    expect((await postVenue(orgA.token, { id, name: `Mv ${id}`, x: 0.2, y: 0.2 })).status).toBe(201);

    const toGeo = await as(orgA.token).put(`/api/manage/venues/${id}`).send({ lat: 16.4862, lng: 80.6935, kind: 'Hall' });
    expect(toGeo.status).toBe(200);
    const xy = latLngToXY(16.4862, 80.6935);
    expect(toGeo.body.x).toBeCloseTo(xy.x, 9);
    expect(toGeo.body.y).toBeCloseTo(xy.y, 9);
    expect(toGeo.body).toMatchObject({ lat: 16.4862, lng: 80.6935, geoPlaced: true, kind: 'Hall' });

    const toXY = await as(orgA.token).put(`/api/manage/venues/${id}`).send({ x: 0.4, y: 0.6, description: 'moved' });
    expect(toXY.status).toBe(200);
    const derived = xyToLatLng(0.4, 0.6);
    expect(toXY.body).toMatchObject({ x: 0.4, y: 0.6, geoPlaced: false, kind: 'Hall', description: 'moved' });
    expect(toXY.body.lat).toBeCloseTo(derived.lat, 9);
    expect(toXY.body.lng).toBeCloseTo(derived.lng, 9);

    const stored = await query('SELECT lat, lng, geo_placed FROM venues WHERE id = $1', [id]);
    expect(stored.rows[0]).toEqual({ lat: null, lng: null, geo_placed: false });
  });

  it('rejects both pairs, half a lat/lng pair, and OUTSIDE_CAMPUS', async () => {
    const id = slug();
    expect((await postVenue(orgA.token, { id, name: `Pu ${id}`, x: 0.3, y: 0.3 })).status).toBe(201);
    const put = (body) => as(orgA.token).put(`/api/manage/venues/${id}`).send(body);
    expect((await put({ x: 0.1, y: 0.1, lat: 16.4855, lng: 80.692 })).status).toBe(400);
    expect((await put({ x: 0.1, lat: 16.4855, lng: 80.692 })).status).toBe(400);
    expect((await put({ lat: 16.4855 })).status).toBe(400);
    const out = await put({ lat: 1, lng: 1 });
    expect(out.status).toBe(400);
    expect(out.body.code).toBe('OUTSIDE_CAMPUS');
    const unchanged = await query('SELECT map_x, map_y FROM venues WHERE id = $1', [id]);
    expect(unchanged.rows[0]).toEqual({ map_x: 0.3, map_y: 0.3 });
  });
});

describe('announcement repeats', () => {
  it('manager announcements repeat to the same room (201 twice); event relays still dedupe', async () => {
    resetAnnouncementThrottle();
    const message = unique('Repeat');
    const post = () => as(orgA.token).post('/api/manage/announcements').send({ message, venueId: 'lobby' });
    expect((await post()).status).toBe(201);
    expect((await post()).status).toBe(201);
    const count = await query('SELECT count(*)::int AS n FROM announcements WHERE message = $1', [message]);
    expect(count.rows[0].n).toBe(2);

    const auto = `geo-auto-${unique('m')}`;
    const first = await recordAnnouncement('lobby', auto);
    const second = await recordAnnouncement('lobby', auto);
    expect(first.created).toBe(true);
    expect(second.created).toBe(false);
    expect(second.announcement.id).toBe(first.announcement.id);
  });
});

describe('admin accounts /api/manage/users', () => {
  it('is admin only: organizer and student get 403, anonymous 401', async () => {
    for (const [method, url] of [
      ['get', '/api/manage/users'],
      ['post', '/api/manage/users'],
      ['put', '/api/manage/users/someone']
    ]) {
      expect((await api()[method](url).send({})).status).toBe(401);
      for (const who of [orgA, student]) {
        const res = await as(who.token)[method](url).send({});
        expect(res.status).toBe(403);
        expect(res.body.code).toBe('FORBIDDEN');
      }
    }
  });

  it('lists users without password hashes', async () => {
    const res = await as(admin.token).get('/api/manage/users');
    expect(res.status).toBe(200);
    const users = res.body.users;
    expect(users.length).toBeGreaterThanOrEqual(3);
    for (const u of users) {
      expect(Object.keys(u).sort()).toEqual(['createdAt', 'email', 'id', 'name', 'role']);
    }
    expect(users.find((u) => u.id === admin.id)).toMatchObject({ role: 'admin' });
  });

  it('creates an organizer who can log in and use /api/manage; 409 on duplicate email; 400 on bad input', async () => {
    const email = `${unique('new-org')}@geo-made.io`;
    const res = await as(admin.token)
      .post('/api/manage/users')
      .send({ name: 'Fresh Organizer', email, password: 'longenough1', role: 'organizer' });
    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({ name: 'Fresh Organizer', email, role: 'organizer' });
    expect(res.body.password).toBeUndefined();
    expect(res.body.password_hash).toBeUndefined();
    created.users.push(res.body.id);

    const dup = await as(admin.token)
      .post('/api/manage/users')
      .send({ name: 'Again', email, password: 'longenough1', role: 'student' });
    expect(dup.status).toBe(409);
    expect(dup.body.code).toBe('EMAIL_TAKEN');

    for (const body of [
      { name: 'x', email, password: 'short', role: 'student' },
      { name: 'x', email: 'nope', password: 'longenough1', role: 'student' },
      { name: 'x', email: `${unique('b')}@geo-made.io`, password: 'longenough1', role: 'superuser' },
      { name: 'x', email: `${unique('b')}@geo-made.io`, password: 'longenough1' },
      { name: '', email: `${unique('b')}@geo-made.io`, password: 'longenough1', role: 'student' }
    ]) {
      expect((await as(admin.token).post('/api/manage/users').send(body)).status, JSON.stringify(body)).toBe(400);
    }

    const login = await api().post('/api/auth/login').send({ email, password: 'longenough1' });
    expect(login.status).toBe(200);
    expect(login.body.user.role).toBe('organizer');
    const overview = await as(login.body.token).get('/api/manage/overview');
    expect(overview.status).toBe(200);
    const users = await as(login.body.token).get('/api/manage/users');
    expect(users.status).toBe(403);
  });

  it('changes roles, 404 USER_NOT_FOUND, 400 CANNOT_DEMOTE_SELF, 400 invalid role', async () => {
    const target = await makeUser('student');
    const up = await as(admin.token).put(`/api/manage/users/${target.id}`).send({ role: 'organizer' });
    expect(up.status).toBe(200);
    expect(up.body).toMatchObject({ id: target.id, role: 'organizer' });
    // The role is read from the DB, so the change is effective immediately.
    expect((await as(target.token).get('/api/manage/overview')).status).toBe(200);
    const down = await as(admin.token).put(`/api/manage/users/${target.id}`).send({ role: 'student' });
    expect(down.body.role).toBe('student');
    expect((await as(target.token).get('/api/manage/overview')).status).toBe(403);

    const missing = await as(admin.token).put('/api/manage/users/usr_nope').send({ role: 'admin' });
    expect(missing.status).toBe(404);
    expect(missing.body.code).toBe('USER_NOT_FOUND');

    const self = await as(admin.token).put(`/api/manage/users/${admin.id}`).send({ role: 'student' });
    expect(self.status).toBe(400);
    expect(self.body.code).toBe('CANNOT_DEMOTE_SELF');
    const same = await as(admin.token).put(`/api/manage/users/${admin.id}`).send({ role: 'admin' });
    expect(same.status).toBe(200);

    expect((await as(admin.token).put(`/api/manage/users/${target.id}`).send({ role: 'root' })).status).toBe(400);
    expect((await as(admin.token).put(`/api/manage/users/${target.id}`).send({})).status).toBe(400);
  });
});
