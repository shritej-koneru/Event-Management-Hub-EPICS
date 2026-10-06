import { describe, expect, it } from 'vitest';
import { getPool } from '../src/db/pool.js';
import { api, as, loginDemo, unique } from './helpers/testContext.js';
import { VENUE_IDS, venueIdToName } from '../src/navigation/venues.js';

const pool = () => getPool();

/**
 * GET /api/events/:id/details — the aggregate Event Details payload.
 *
 * The recurring theme here is that nothing may be invented: every statistic must
 * equal the value actually stored in the database, and charts must contain only
 * real data points rather than padding to make a chart look complete.
 */
describe('GET /api/events/:id/details', () => {
  it('returns the aggregate payload an Event Details screen needs', async () => {
    const response = await api().get('/api/events/evt_001/details');

    expect(response.status).toBe(200);
    expect(Object.keys(response.body).sort()).toEqual([
      'announcements',
      'charts',
      'event',
      'navigation',
      'statistics',
      'venue'
    ]);
  });

  it('reuses the canonical Event shape unchanged inside the payload', async () => {
    const [{ body: detail }, { body: single }] = await Promise.all([
      api().get('/api/events/evt_001/details'),
      api().get('/api/events/evt_001')
    ]);

    // The aggregate endpoint must not fork the event representation.
    expect(detail.event).toEqual(single.event);
  });

  it('keeps GET /api/events/:id in its existing { event } envelope', async () => {
    const { body } = await api().get('/api/events/evt_001');

    // The Android EventResponse data class decodes exactly this.
    expect(Object.keys(body)).toEqual(['event']);
  });

  it('reports statistics that match the stored columns exactly', async () => {
    const { rows } = await pool().query(
      'SELECT capacity, registered FROM events WHERE id = $1',
      ['evt_001']
    );
    const { body } = await api().get('/api/events/evt_001/details');

    expect(body.statistics.capacity).toBe(rows[0].capacity);
    expect(body.statistics.registered).toBe(rows[0].registered);
    expect(body.statistics.seatsRemaining).toBe(rows[0].capacity - rows[0].registered);
    expect(body.statistics.occupancyPercent).toBeCloseTo(
      (rows[0].registered / rows[0].capacity) * 100,
      1
    );
    expect(body.statistics.durationMin).toBeGreaterThan(0);
  });

  it('never claims the caller personally registered', async () => {
    const { body } = await api().get('/api/events/evt_001/details');

    // Registration state lives on the Android device, so the backend must not
    // expose any "you are registered" style flag.
    const statisticKeys = Object.keys(body.statistics).map((key) => key.toLowerCase());
    expect(statisticKeys).not.toContain('userregistered');
    expect(statisticKeys).not.toContain('isregistered');
    expect(statisticKeys).not.toContain('attended');
    expect(statisticKeys).not.toContain('myregistration');
  });

  it('derives isPast and daysUntilEvent from the canonical date field', async () => {
    const { body } = await api().get('/api/events/evt_001/details');
    const today = new Date().toISOString().slice(0, 10);
    const expected = Math.round(
      (Date.parse(`${body.event.date}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000
    );

    expect(body.event.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(body.statistics.daysUntilEvent).toBe(expected);
    expect(body.statistics.isPast).toBe(expected < 0);
  });

  it('uses no startDate, startTime or endDate concept', async () => {
    const { body } = await api().get('/api/events/evt_001/details');
    const serialized = JSON.stringify(body).toLowerCase();

    for (const forbidden of ['startdate', 'starttime', 'enddate', 'endtime']) {
      expect(serialized).not.toContain(forbidden);
    }
    // The established fields remain.
    expect(body.event.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(typeof body.event.time).toBe('string');
  });

  it('describes the venue using real navigation graph data', async () => {
    const { body } = await api().get('/api/events/evt_001/details');

    expect(VENUE_IDS).toContain(body.venue.id);
    expect(body.venue.name).toBe(venueIdToName.get(body.venue.id));
    expect(body.venue.navigationNode).toBe(body.venue.id);
    for (const neighbor of body.venue.neighbors) {
      expect(VENUE_IDS).toContain(neighbor);
    }
  });

  it('includes wayfinding from the entrance by default', async () => {
    const { body } = await api().get('/api/events/evt_001/details');

    expect(body.navigation.from).toBe('entrance');
    expect(body.navigation.nodes[0].id).toBe('entrance');
    expect(body.navigation.nodes.at(-1).id).toBe(body.venue.id);
    expect(body.navigation.totalDistanceM).toBeGreaterThan(0);
    expect(body.navigation.steps.length).toBeGreaterThan(0);
  });

  it('honours an explicit from node', async () => {
    const { body } = await api().get('/api/events/evt_001/details?from=cafeteria');

    expect(body.navigation.from).toBe('cafeteria');
    expect(body.navigation.nodes[0].id).toBe('cafeteria');
    expect(body.navigation.nodes.at(-1).id).toBe('room-204');
  });

  it('rejects an unknown from node', async () => {
    // Semantic resolution happens in the navigation service, exactly as it does for
// GET /api/events/:id/route, so the error code must match that endpoint too.
const response = await api().get('/api/events/evt_001/details?from=not-a-node');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('404s for an unknown event', async () => {
    const response = await api().get(`/api/events/${unique('evt_missing')}/details`);

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('EVENT_NOT_FOUND');
  });

  it('reports isBookmarked for the caller, false when anonymous', async () => {
    const anonymous = await api().get('/api/events/evt_001/details');
    const { token } = await loginDemo();
    const authenticated = as(token).get('/api/events/evt_001/details');
    const authed = await authenticated;

    // evt_001 is in the demo user's seeded bookmarks.
    expect(anonymous.body.statistics.isBookmarked).toBe(false);
    expect(authed.body.statistics.isBookmarked).toBe(true);
  });

  it('reflects a bookmark the caller adds, without claiming registration', async () => {
    const { token } = await loginDemo();
    await as(token).delete('/api/auth/me/bookmarks/evt_002');
    await as(token).post('/api/auth/me/bookmarks/evt_002');

    const { body } = await as(token).get('/api/events/evt_002/details');

    expect(body.statistics.isBookmarked).toBe(true);
    expect(body.statistics.registered).toBe(body.event.registered);

    await as(token).delete('/api/auth/me/bookmarks/evt_002');
  });

  it('works for every event in the catalogue', async () => {
    const { body: list } = await api().get('/api/events');
    expect(list.events.length).toBeGreaterThan(0);

    for (const event of list.events) {
      const response = await api().get(`/api/events/${event.id}/details`);

      expect(response.status, `${event.id} should resolve`).toBe(200);
      expect(response.body.event.id).toBe(event.id);
      expect(response.body.venue.id).toBe(event.venueId);
      expect(response.body.statistics.capacity).toBe(event.capacity);
      expect(response.body.statistics.registered).toBe(event.registered);
      expect(response.body.statistics.seatsRemaining).toBeGreaterThanOrEqual(0);
      expect(response.body.navigation.nodes.at(-1).id).toBe(event.venueId);
      expect(Array.isArray(response.body.charts)).toBe(true);
    }
  });
});

describe('GET /api/events/:id/details charts', () => {
  it('only contains charts built from real data points', async () => {
    const { body } = await api().get('/api/events/evt_001/details');

    for (const chart of body.charts) {
      expect(typeof chart.id).toBe('string');
      expect(['donut', 'bar', 'line']).toContain(chart.type);
      expect(Array.isArray(chart.data)).toBe(true);
      for (const point of chart.data) {
        expect(typeof point.label).toBe('string');
        expect(typeof point.value).toBe('number');
        expect(Number.isFinite(point.value)).toBe(true);
        expect(point.value).toBeGreaterThanOrEqual(0);
      }
    }
  });

  it('sums the occupancy series to the real capacity', async () => {
    const { body } = await api().get('/api/events/evt_001/details');
    const occupancy = body.charts.find((chart) => chart.id === 'occupancy');

    expect(occupancy).toBeDefined();
    expect(occupancy.total).toBe(body.event.capacity);
    const sum = occupancy.data.reduce((total, point) => total + point.value, 0);
    expect(sum).toBe(body.event.capacity);
  });

  it('splits the category series against the real event total', async () => {
    const { rows } = await pool().query(
      `SELECT COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE lower(category) = 'tech')::int AS tech
         FROM events`
    );
    const { body } = await api().get('/api/events/evt_001/details');
    const mix = body.charts.find((chart) => chart.id === 'categoryMix');

    expect(mix.total).toBe(rows[0].total);
    const sum = mix.data.reduce((total, point) => total + point.value, 0);
    expect(sum).toBe(rows[0].total);
  });

  it('omits the announcement trend when there is not enough real data', async () => {
    const { body } = await api().get('/api/events/evt_002/details');
    const trend = body.charts.find((chart) => chart.id === 'announcementTrend');

    // evt_002 has no announcements, so no trend may be drawn for it.
    expect(trend).toBeUndefined();
  });

  it('omits the occupancy chart when capacity is zero', async () => {
    const capacity = unique('cap');
    await pool().query(
      `INSERT INTO events (id, title, category, venue_id, date, time, duration_min, capacity, registered, host, description, tags, icon)
       VALUES ($1, 'Zero Capacity Event', 'Tech', 'room-204', '2026-12-01', '10:00', 60, 0, 0, 'Host', 'A zero-capacity event for chart coverage.', '["tech"]'::jsonb, 'X')`,
      [capacity]
    );

    try {
      const { body } = await api().get(`/api/events/${capacity}/details`);

      expect(body.statistics.capacity).toBe(0);
      expect(body.statistics.occupancyPercent).toBe(0);
      // A zero-capacity donut would be a division by zero on the client.
      expect(body.charts.find((chart) => chart.id === 'occupancy')).toBeUndefined();
    } finally {
      await pool().query('DELETE FROM events WHERE id = $1', [capacity]);
    }
  });
});

describe('GET /api/chat/announcements?eventId=', () => {
  it('returns only the requested event announcements', async () => {
    const { body } = await api().get('/api/chat/announcements?eventId=evt_001');

    expect(body.eventId).toBe('evt_001');
    expect(body.announcements.length).toBeGreaterThan(0);
    for (const announcement of body.announcements) {
      expect(announcement.eventId).toBe('evt_001');
      expect(announcement.room).toBe('room-204');
    }
  });

  it('does not leak another event at the same venue', async () => {
    // evt_002 (Music Night) and evt_006 (Drama) are both held at "auditorium".
    const { body: list } = await api().get('/api/events?venue=Auditorium');
    const [first, second] = list.events;
    expect(first.venueId).toBe(second.venueId);

    const [a, b] = await Promise.all([
      api().get(`/api/chat/announcements?eventId=${first.id}`),
      api().get(`/api/chat/announcements?eventId=${second.id}`)
    ]);

    expect(a.body.announcements.every((item) => item.eventId === first.id)).toBe(true);
    expect(b.body.announcements.every((item) => item.eventId === second.id)).toBe(true);

    const idsA = a.body.announcements.map((item) => item.message);
    const idsB = b.body.announcements.map((item) => item.message);
    for (const message of idsA) expect(idsB).not.toContain(message);
  });

  it('returns an empty list, not an error, for an event with no announcements', async () => {
    const id = unique('evt_quiet');
    await pool().query(
      `INSERT INTO events (id, title, category, venue_id, date, time, duration_min, capacity, registered, host, description, tags, icon)
       VALUES ($1, 'Quiet Event', 'Arts', 'library', '2026-12-02', '11:00', 45, 10, 0, 'Host', 'An event with no announcements.', '["arts"]'::jsonb, 'Q')`,
      [id]
    );

    try {
      const response = await api().get(`/api/chat/announcements?eventId=${id}`);

      expect(response.status).toBe(200);
      expect(response.body.announcements).toEqual([]);
      expect(response.body.eventId).toBe(id);
    } finally {
      await pool().query('DELETE FROM events WHERE id = $1', [id]);
    }
  });

  it('404s for an unknown event id', async () => {
    const response = await api().get(`/api/chat/announcements?eventId=${unique('evt_nope')}`);

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('EVENT_NOT_FOUND');
  });

  it('keeps the unfiltered venue-wide feed working', async () => {
    const { body } = await api().get('/api/chat/announcements');

    expect(Array.isArray(body.announcements)).toBe(true);
    expect(body.eventId).toBeUndefined();
  });

  it('rejects a malformed eventId', async () => {
    const response = await api().get("/api/chat/announcements?eventId=' OR 1=1");

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('links a pre-existing venue-level row to its event on re-save', async () => {
    // Simulates a row that existed before migration 003, whose text carries no
    // "[evt_...]" suffix so the migration backfill cannot infer the event.
    const message = 'legacy venue level notice';
    await pool().query(
      'INSERT INTO announcements (room, message) VALUES ($1, $2)',
      ['room-204', message]
    );
    const { recordAnnouncement } = await import('../src/models/announcementModel.js');

    try {
      const before = await pool().query(
        'SELECT event_id FROM announcements WHERE message = $1',
        [message]
      );
      expect(before.rows[0].event_id).toBeNull();

      const result = await recordAnnouncement('room-204', message, 'evt_001');

      // Not a duplicate insert...
      expect(result.created).toBe(false);
      // ...but the association is now known.
      expect(result.announcement.eventId).toBe('evt_001');

      const scoped = await api().get('/api/chat/announcements?eventId=evt_001');
      expect(scoped.body.announcements.map((a) => a.message)).toContain(message);
    } finally {
      await pool().query('DELETE FROM announcements WHERE message = $1', [message]);
    }
  });

  it('never overwrites an association that is already known', async () => {
    const message = 'association guard notice';
    await pool().query(
      'INSERT INTO announcements (room, message, event_id) VALUES ($1, $2, $3)',
      ['room-204', message, 'evt_001']
    );
    const { recordAnnouncement } = await import('../src/models/announcementModel.js');

    try {
      await recordAnnouncement('room-204', message, 'evt_003');

      const { rows } = await pool().query(
        'SELECT event_id FROM announcements WHERE message = $1',
        [message]
      );
      expect(rows[0].event_id).toBe('evt_001');
    } finally {
      await pool().query('DELETE FROM announcements WHERE message = $1', [message]);
    }
  });

  it('surfaces each event feed in the details payload', async () => {
    const { body } = await api().get('/api/events/evt_001/details');

    for (const announcement of body.announcements) {
      expect(announcement.eventId).toBe('evt_001');
    }
    expect(body.statistics.announcementCount).toBe(body.announcements.length);
  });
});