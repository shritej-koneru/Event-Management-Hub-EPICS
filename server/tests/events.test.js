import { beforeAll, describe, expect, it } from 'vitest';
import { api, as, loginDemo, unique } from './helpers/testContext.js';
import { VENUE_IDS, venueIdToName } from '../src/navigation/venues.js';

/** Field set the Android `Event` data class requires. */
const REQUIRED_EVENT_FIELDS = [
  'id', 'title', 'category', 'venue', 'venueId', 'date',
  'time', 'durationMin', 'capacity', 'registered',
  'host', 'description', 'tags', 'icon'
];

describe('GET /api/events', () => {
  it('returns the seeded events from the database', async () => {
    const response = await api().get('/api/events');

    expect(response.status).toBe(200);
    expect(Array.isArray(response.body.events)).toBe(true);
    expect(response.body.events.length).toBeGreaterThanOrEqual(12);
  });

  it('matches the contract Event shape exactly', async () => {
    const { body } = await api().get('/api/events');

    for (const event of body.events) {
      expect(Object.keys(event).sort()).toEqual([...REQUIRED_EVENT_FIELDS].sort());
      expect(typeof event.id).toBe('string');
      expect(typeof event.capacity).toBe('number');
      expect(typeof event.registered).toBe('number');
      expect(Array.isArray(event.tags)).toBe(true);
    }
  });

  it('serves date as a plain YYYY-MM-DD string (not a JS Date)', async () => {
    const { body } = await api().get('/api/events');
    expect(body.events[0].date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('stores every event venue as a canonical navigation graph node', async () => {
    const { body } = await api().get('/api/events');

    for (const event of body.events) {
      expect(VENUE_IDS).toContain(event.venueId);
      expect(event.venue).toBe(venueIdToName.get(event.venueId));
    }
  });

  it('filters by category', async () => {
    const { body } = await api().get('/api/events?category=Tech');
    expect(body.events.length).toBeGreaterThan(0);
    expect(body.events.every((event) => event.category === 'Tech')).toBe(true);
  });

  it('filters by date', async () => {
    const { body } = await api().get('/api/events?date=2026-10-15');
    expect(body.events.map((e) => e.id)).toContain('evt_001');
    expect(body.events.every((event) => event.date === '2026-10-15')).toBe(true);
  });

  it('filters by free-text query', async () => {
    const { body } = await api().get('/api/events?q=yoga');
    expect(body.events.map((e) => e.id)).toContain('evt_004');
  });

  it('rejects a malformed date filter with 400', async () => {
    const response = await api().get('/api/events?date=15-10-2026');
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('filters by venue id', async () => {
    const { body } = await api().get('/api/events?venue=room-204');

    expect(body.events.length).toBeGreaterThan(0);
    expect(body.events.every((event) => event.venueId === 'room-204')).toBe(true);
  });

  it('filters by venue display name', async () => {
    const byId = await api().get('/api/events?venue=room-204');
    const byName = await api().get('/api/events?venue=Room%20204');

    expect(byName.body.events.map((e) => e.id)).toEqual(byId.body.events.map((e) => e.id));
  });

  it('matches the category filter case-insensitively', async () => {
    const lower = await api().get('/api/events?category=tech');
    const upper = await api().get('/api/events?category=TECH');
    const mixed = await api().get('/api/events?category=TeCh');

    expect(lower.body.events.length).toBeGreaterThan(0);
    expect(upper.body.events.map((e) => e.id)).toEqual(lower.body.events.map((e) => e.id));
    expect(mixed.body.events.map((e) => e.id)).toEqual(lower.body.events.map((e) => e.id));
  });

  it('matches the free-text filter case-insensitively', async () => {
    const { body } = await api().get('/api/events?q=ROBOTICS');
    expect(body.events.map((e) => e.id)).toContain('evt_007');
  });

  it('free-text filter also matches tags', async () => {
    const { body } = await api().get('/api/events?q=spoken-word');
    expect(body.events.map((e) => e.id)).toContain('evt_009');
  });

  it('combines category and venue filters', async () => {
    const { body } = await api().get('/api/events?category=tech&venue=main-hall');

    expect(body.events.length).toBeGreaterThan(0);
    for (const event of body.events) {
      expect(event.category).toBe('Tech');
      expect(event.venueId).toBe('main-hall');
    }
  });

  it('combines venue, date and free-text filters', async () => {
    const { body } = await api().get('/api/events?venue=main-hall&date=2026-10-18&q=tech');

    expect(body.events.map((e) => e.id)).toEqual(['evt_003']);
  });

  it('returns an empty list when filters exclude everything', async () => {
    const { body } = await api().get('/api/events?category=NoSuchCategory');
    expect(body.events).toEqual([]);
  });

  it('rejects an unsupported query parameter with 400', async () => {
    const response = await api().get('/api/events?catgeory=Tech');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});

describe('GET /api/events pagination', () => {
  it('returns pagination metadata alongside the page', async () => {
    const response = await api().get('/api/events?page=1&pageSize=5');

    expect(response.status).toBe(200);
    expect(response.body.events).toHaveLength(5);
    expect(response.body.pagination).toMatchObject({
      page: 1,
      pageSize: 5,
      hasMore: true
    });
    expect(response.body.pagination.total).toBeGreaterThanOrEqual(12);
    expect(response.body.pagination.totalPages).toBeGreaterThan(1);
  });

  it('keeps the events key so the Android EventListResponse still parses', async () => {
    const response = await api().get('/api/events?page=1&pageSize=3');

    expect(Array.isArray(response.body.events)).toBe(true);
    for (const event of response.body.events) {
      expect(Object.keys(event).sort()).toEqual([...REQUIRED_EVENT_FIELDS].sort());
    }
  });

  it('returns disjoint pages that together cover the catalogue', async () => {
    const first = await api().get('/api/events?page=1&pageSize=5');
    const second = await api().get('/api/events?page=2&pageSize=5');
    const third = await api().get('/api/events?page=3&pageSize=5');

    const ids = [
      ...first.body.events.map((e) => e.id),
      ...second.body.events.map((e) => e.id),
      ...third.body.events.map((e) => e.id)
    ];

    expect(new Set(ids).size).toBe(ids.length);
    expect(first.body.pagination.total).toBe(second.body.pagination.total);
    expect(first.body.pagination.total).toBe(third.body.pagination.total);
  });

  it('reports hasMore false on the final page', async () => {
    const { body: all } = await api().get('/api/events');
    const totalPages = Math.ceil(all.events.length / 4);

    const last = await api().get(`/api/events?page=${totalPages}&pageSize=4`);
    expect(last.body.pagination.hasMore).toBe(false);
  });

  it('returns an empty page past the end of the result set', async () => {
    const response = await api().get('/api/events?page=999&pageSize=10');

    expect(response.status).toBe(200);
    expect(response.body.events).toEqual([]);
  });

  it('counts total against the filtered set, not the whole catalogue', async () => {
    const { body } = await api().get('/api/events?category=tech&page=1&pageSize=2');
    const filtered = await api().get('/api/events?category=tech');
    const all = await api().get('/api/events');

    expect(body.pagination.total).toBe(filtered.body.events.length);
    expect(body.pagination.total).toBeLessThan(all.body.events.length);
  });

  it('omits pagination when the caller does not paginate', async () => {
    const { body } = await api().get('/api/events');
    expect(body.pagination).toBeUndefined();
  });

  it('rejects page 0 with 400', async () => {
    const response = await api().get('/api/events?page=0');
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects an oversized pageSize with 400', async () => {
    const response = await api().get('/api/events?pageSize=5000');
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a non-numeric page with 400', async () => {
    const response = await api().get('/api/events?page=abc');
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});

describe('GET /api/events/:id', () => {
  it('returns a single event', async () => {
    const response = await api().get('/api/events/evt_001');

    expect(response.status).toBe(200);
    expect(response.body.event.id).toBe('evt_001');
    expect(response.body.event.venue).toBe('Room 204');
    expect(response.body.event.venueId).toBe('room-204');
  });

  it('returns 404 EVENT_NOT_FOUND for an unknown id', async () => {
    const response = await api().get('/api/events/evt_does_not_exist');

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('EVENT_NOT_FOUND');
    expect(response.body.error).toMatch(/not found/i);
  });

  it('rejects a malformed event id with 400 before hitting the database', async () => {
    const response = await api().get('/api/events/bad%20id!');
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});



describe('POST /api/events', () => {
  const validPayload = () => ({
    title: unique('Test Event'),
    category: 'Tech',
    venue: 'Room 205',
    date: '2027-06-01',
    time: '11:00',
    capacity: 25,
    host: 'Tester',
    description: 'Created by the test suite',
    tags: ['test']
  });

  /** Event creation requires a JWT per contract/API-CONTRACT.md. */
  let token;
  beforeAll(async () => {
    ({ token } = await loginDemo());
  });

  it('requires authentication', async () => {
    const response = await api().post('/api/events').send(validPayload());

    expect(response.status).toBe(401);
    expect(response.body.code).toBe('AUTH_REQUIRED');
  });

  it('rejects an invalid token with 401', async () => {
    const response = await as('not-a-real-token').post('/api/events').send(validPayload());
    expect(response.status).toBe(401);
  });

  it('creates an event and returns 201', async () => {
    const response = await as(token).post('/api/events').send(validPayload());

    expect(response.status).toBe(201);
    expect(response.body.event.id).toMatch(/^evt_/);
    expect(response.body.event.venue).toBe('Room 205');
    expect(response.body.event.venueId).toBe('room-205');
    expect(response.body.event.registered).toBe(0);
  });

  it('persists the created event so GET returns it', async () => {
    const payload = validPayload();
    const created = await as(token).post('/api/events').send(payload);

    const fetched = await api().get(`/api/events/${created.body.event.id}`);
    expect(fetched.status).toBe(200);
    expect(fetched.body.event.title).toBe(payload.title);
  });

  it('rejects a non-canonical venue spelling with 400 UNKNOWN_VENUE', async () => {
    const response = await as(token)
      .post('/api/events')
      .send({ ...validPayload(), venue: 'Room205' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('rejects an invented venue with 400 UNKNOWN_VENUE', async () => {
    const response = await as(token)
      .post('/api/events')
      .send({ ...validPayload(), venue: 'Space Elevator' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('rejects a missing capacity with 400', async () => {
    const payload = validPayload();
    delete payload.capacity;

    const response = await as(token).post('/api/events').send(payload);
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a malformed date with 400', async () => {
    const response = await as(token)
      .post('/api/events')
      .send({ ...validPayload(), date: '01/06/2027' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects registered greater than capacity with 400', async () => {
    const response = await as(token)
      .post('/api/events')
      .send({ ...validPayload(), capacity: 10, registered: 50 });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});

describe('GET /api/events/:id/route', () => {
  it('routes from the entrance to the event venue by default', async () => {
    const response = await api().get('/api/events/evt_001/route');

    expect(response.status).toBe(200);
    expect(response.body.eventId).toBe('evt_001');
    expect(response.body.venueId).toBe('room-204');
    expect(response.body.venue).toBe('Room 204');
    expect(response.body.nodes[0].id).toBe('entrance');
    expect(response.body.nodes.at(-1).id).toBe('room-204');
    expect(response.body.totalDistanceM).toBeGreaterThan(0);
    expect(Array.isArray(response.body.steps)).toBe(true);
  });

  it('accepts an explicit from node id', async () => {
    const response = await api().get('/api/events/evt_001/route?from=library');

    expect(response.status).toBe(200);
    expect(response.body.nodes[0].id).toBe('library');
    expect(response.body.nodes.at(-1).id).toBe('room-204');
  });

  it('accepts a canonical display name for from', async () => {
    const byId = await api().get('/api/events/evt_001/route?from=library');
    const byName = await api().get('/api/events/evt_001/route?from=Library');

    expect(byName.body.nodes.map((n) => n.id)).toEqual(byId.body.nodes.map((n) => n.id));
  });

  it('matches the shape of POST /api/navigation/path', async () => {
    const viaEvent = await api().get('/api/events/evt_001/route?from=entrance');
    const viaPath = await api()
      .post('/api/navigation/path')
      .send({ from: 'entrance', to: 'Room 204' });

    expect(viaEvent.body.nodes.map((n) => n.id)).toEqual(viaPath.body.nodes.map((n) => n.id));
    expect(viaEvent.body.steps.length).toBe(viaPath.body.steps.length);
  });

  it('routes to every seeded event venue', async () => {
    const { body } = await api().get('/api/events');

    for (const event of body.events.slice(0, 12)) {
      const response = await api().get(`/api/events/${event.id}/route`);
      expect(response.status, event.id).toBe(200);
      expect(response.body.nodes.at(-1).id, event.id).toBe(event.venueId);
    }
  });

  it('returns 404 EVENT_NOT_FOUND for an unknown event', async () => {
    const response = await api().get('/api/events/evt_nope/route');

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('EVENT_NOT_FOUND');
  });

  it('rejects an unknown from venue with 400 UNKNOWN_VENUE', async () => {
    const response = await api().get('/api/events/evt_001/route?from=Space%20Elevator');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('rejects an unsupported route query parameter with 400', async () => {
    const response = await api().get('/api/events/evt_001/route?start=entrance');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('still serves the plain event detail for the same id', async () => {
    const response = await api().get('/api/events/evt_001');
    expect(response.body.event.id).toBe('evt_001');
  });
});
