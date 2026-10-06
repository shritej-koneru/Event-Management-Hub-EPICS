import { describe, expect, it } from 'vitest';
import { query } from '../src/db/pool.js';
import { resolveSeedDemoUser } from '../src/config/index.js';
import { seed } from '../src/db/seeds/index.js';
import { DEMO_USER, SEED_ANNOUNCEMENTS } from '../src/db/seeds/users.js';
import { SEED_EVENTS } from '../src/db/seeds/events.js';
import { VENUES, venueIdToName } from '../src/navigation/venues.js';
import { shortestPath } from '../src/navigation/graph.js';

/**
 * Guards the contract's hard requirement: every event's `venue` must equal a
 * navigation graph node display name, and that node must be routable.
 */
describe('seed data integrity', () => {
  it('every seeded event carries id, title, category, venue, date and capacity', () => {
    for (const event of SEED_EVENTS) {
      expect(event.id, `${event.id} id`).toMatch(/^evt_\w+$/);
      expect(event.title).toBeTruthy();
      expect(event.category).toBeTruthy();
      expect(event.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(event.capacity).toBeGreaterThan(0);
      expect(event.registered).toBeLessThanOrEqual(event.capacity);
    }
  });

  it('every seeded event venue is a canonical navigation graph node', () => {
    const valid = new Set(VENUES.map((v) => v.id));

    for (const event of SEED_EVENTS) {
      expect(valid.has(event.venueId), `${event.id} -> ${event.venueId}`).toBe(true);
      expect(venueIdToName.get(event.venueId)).toBeTruthy();
    }
  });

  it('the database stores exactly the seeded venues', async () => {
    const { rows } = await query('SELECT id, name FROM venues ORDER BY id');
    expect(rows).toHaveLength(VENUES.length);

    for (const venue of VENUES) {
      expect(rows.find((row) => row.id === venue.id)?.name).toBe(venue.name);
    }
  });

  it('the database stores every seeded event', async () => {
    const { rows } = await query('SELECT id FROM events');
    const stored = new Set(rows.map((row) => row.id));

    for (const event of SEED_EVENTS) {
      expect(stored.has(event.id), `${event.id} missing from database`).toBe(true);
    }
  });

  it('no stored event references a venue outside the graph', async () => {
    const { rows } = await query('SELECT id, venue_id FROM events');
    const valid = new Set(VENUES.map((v) => v.id));

    for (const row of rows) {
      expect(valid.has(row.venue_id), `${row.id} -> ${row.venue_id}`).toBe(true);
    }
  });

  it('every stored event venue is routable from Entrance', async () => {
    const { rows } = await query('SELECT id, venue_id FROM events');

    for (const row of rows) {
      const path = shortestPath('entrance', row.venue_id);
      expect(path.nodeIds.at(-1), `${row.id} unreachable`).toBe(row.venue_id);
    }
  });

  it('the demo user exists with interests, bookmarks and history', async () => {
    const user = await query('SELECT id, email, interests FROM users WHERE email = $1', ['alice@uni.edu']);
    expect(user.rows).toHaveLength(1);
    expect(user.rows[0].interests).toContain('tech');

    const bookmarks = await query('SELECT event_id FROM bookmarks WHERE user_id = $1', [user.rows[0].id]);
    expect(bookmarks.rows.map((r) => r.event_id)).toEqual(
      expect.arrayContaining(['evt_001', 'evt_005'])
    );

    const history = await query('SELECT query FROM search_history WHERE user_id = $1', [user.rows[0].id]);
    expect(history.rows.length).toBeGreaterThan(0);
  });

it('stores event tags as jsonb arrays and emoji icons intact', async () => {
    const { rows } = await query('SELECT tags, icon FROM events WHERE id = $1', ['evt_001']);
    expect(Array.isArray(rows[0].tags)).toBe(true);
    expect(rows[0].tags).toContain('android');
    expect(rows[0].icon.codePointAt(0)).toBeGreaterThan(0xffff); // 4-byte emoji
  });

  it('scopes every seeded announcement to a real event', async () => {
    // Scoped to the seeded rows by message: other suites write probe
    // announcements into the same shared test database.
    expect(SEED_ANNOUNCEMENTS.length).toBeGreaterThan(0);

    for (const announcement of SEED_ANNOUNCEMENTS) {
      const { rows } = await query(
        'SELECT room, event_id FROM announcements WHERE message = $1',
        [announcement.message]
      );

      expect(rows, `seeded announcement "${announcement.message}"`).toHaveLength(1);
      expect(rows[0].event_id).toBe(announcement.eventId);

      const { rows: owner } = await query('SELECT venue_id FROM events WHERE id = $1', [
        rows[0].event_id
      ]);
      expect(owner).toHaveLength(1);
      // The announcement is broadcast into the event's own venue.
      expect(rows[0].room).toBe(owner[0].venue_id);
    }
  });

  it('re-seeding never duplicates an announcement', async () => {
    const before = await query('SELECT COUNT(*)::int AS count FROM announcements');
    await seed({ log: () => {} });
    const after = await query('SELECT COUNT(*)::int AS count FROM announcements');

    expect(after.rows[0].count).toBe(before.rows[0].count);
  });
});
/**
 * The demo account ships with a password published in this repository, so it
 * must not be created automatically outside development.
 */
describe('demo user seeding policy', () => {
  it('defaults to disabled in production', () => {
    expect(resolveSeedDemoUser({}, true)).toBe(false);
    expect(resolveSeedDemoUser({ NODE_ENV: 'production' }, true)).toBe(false);
  });

  it('defaults to enabled in development and test', () => {
    expect(resolveSeedDemoUser({}, false)).toBe(true);
  });

  it('honours an explicit opt-in in production', () => {
    expect(resolveSeedDemoUser({ SEED_DEMO_USER: 'true' }, true)).toBe(true);
  });

  it('honours an explicit opt-out in development', () => {
    expect(resolveSeedDemoUser({ SEED_DEMO_USER: 'false' }, false)).toBe(false);
    expect(resolveSeedDemoUser({ SEED_DEMO_USER: '0' }, false)).toBe(false);
  });

  it('does not create the demo user when seeding with demoUser disabled', async () => {
    const usersBefore = (await query('SELECT id FROM users')).rows.length;

    await query('DELETE FROM users WHERE email = $1', [DEMO_USER.email]);
    const counts = await seed({ log: () => {}, demoUser: false });

    const demo = await query('SELECT id FROM users WHERE email = $1', [DEMO_USER.email]);
    expect(demo.rows).toHaveLength(0);
    expect(counts.demoUser).toBe(false);
    expect(counts.users).toBe(usersBefore - 1);

    // Restore state for every other suite.
    await seed({ log: () => {}, demoUser: true });
    const restored = await query('SELECT id FROM users WHERE email = $1', [DEMO_USER.email]);
    expect(restored.rows).toHaveLength(1);
  });

it('never writes the demo password to the seed log', async () => {
    const lines = [];
    // Delete first so the account-creation branch actually executes.
    await query('DELETE FROM users WHERE email = $1', [DEMO_USER.email]);

    try {
      await seed({ log: (line) => lines.push(line), demoUser: true });
      const output = lines.join('\n');

      expect(output).toContain('demo user');
      expect(output).not.toContain(DEMO_USER.password);
    } finally {
      await seed({ log: () => {}, demoUser: true });
    }
  });
});
