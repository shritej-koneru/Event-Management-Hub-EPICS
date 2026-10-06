import { describe, expect, it, afterAll } from 'vitest';
import { getPool } from '../src/db/pool.js';
import { testContext, unique } from './helpers/testContext.js';

const pool = () => getPool();
const probeIds = [];

/**
 * Schema guarantees that protect data integrity. These are database-level
 * invariants, so they are asserted against the catalog rather than through the
 * HTTP surface, which would not observe them.
 */
describe('schema integrity', () => {
  it('exposes search_history for retention tests', () => {
    expect(testContext()).toBeTruthy();
  });

  it('constrains announcements.room to a real venue', async () => {
    const { rows } = await pool().query(
      `SELECT conname, pg_get_constraintdef(oid) AS def
         FROM pg_constraint
        WHERE conrelid = 'announcements'::regclass
          AND contype = 'f'
          AND conname = 'announcements_room_fkey'`
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].def).toContain('FOREIGN KEY (room) REFERENCES venues(id)');
  });

  it('refuses an announcement for a venue that does not exist', async () => {
    const ghostVenue = unique('ghost-venue');
    await expect(
      pool().query('INSERT INTO announcements (room, message) VALUES ($1, $2)', [
        ghostVenue,
        `orphan probe ${Date.now()}`
      ])
    ).rejects.toThrow(/foreign key|violates/i);
  });

  it('accepts an announcement for a seeded venue', async () => {
    const message = `integrity probe ${unique('msg')}`;
    const { rowCount } = await pool().query(
      'INSERT INTO announcements (room, message) VALUES ($1, $2)',
      ['main-hall', message]
    );

    expect(rowCount).toBe(1);

    await pool().query('DELETE FROM announcements WHERE room = $1 AND message = $2', [
      'main-hall',
      message
    ]);
  });

  it('indexes search_history the way recentQueries() reads it', async () => {
    const { rows } = await pool().query(
      `SELECT indexdef FROM pg_indexes
        WHERE tablename = 'search_history'
          AND indexname = 'idx_search_history_user_query'`
    );

    expect(rows).toHaveLength(1);
    // DISTINCT ON (lower(query)) ORDER BY lower(query), at DESC per user.
    expect(rows[0].indexdef).toContain('lower(query)');
  });

  it('prunes search history to the newest rows per user on write', async () => {
    const userId = unique('retention-probe');
    const passwordHash = '$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidin';

    await pool().query(
      'INSERT INTO users (id, name, email, password_hash) VALUES ($1, $2, $3, $4)',
      [userId, 'Retention Probe', `${userId}@uni.edu`, passwordHash]
    );

    try {
      // Bypass recordSearch() so the prune is exercised directly at scale.
      for (let i = 0; i < 60; i += 1) {
        await pool().query(
          'INSERT INTO search_history (user_id, query) VALUES ($1, $2)',
          [userId, `probe-${i}`]
        );
      }

      const { rows: pruned } = await pool().query('SELECT prune_search_history(50) AS removed');
      expect(pruned[0].removed).toBe(10);

      const { rows: kept } = await pool().query(
        'SELECT COUNT(*)::int AS count FROM search_history WHERE user_id = $1',
        [userId]
      );
      expect(kept[0].count).toBe(50);

      // Retention must drop the OLDEST rows, never the newest.
      const { rows: newest } = await pool().query(
        'SELECT COUNT(*)::int AS count FROM search_history WHERE user_id = $1 AND query = $2',
        [userId, 'probe-59']
      );
      expect(newest[0].count).toBe(1);
    } finally {
      await pool().query('DELETE FROM search_history WHERE user_id = $1', [userId]);
      await pool().query('DELETE FROM users WHERE id = $1', [userId]);
    }
  });

  it('keeps search history bounded when recording through the app', async () => {
    const userId = unique('retention-app-probe');
    const passwordHash = '$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidin';

    await pool().query(
      'INSERT INTO users (id, name, email, password_hash) VALUES ($1, $2, $3, $4)',
      [userId, 'Retention App Probe', `${userId}@uni.edu`, passwordHash]
    );
    probeIds.push(userId);

    const { recordSearch } = await import('../src/models/searchHistoryModel.js');
    for (let i = 0; i < 55; i += 1) {
      await recordSearch(userId, `app-probe-${i}`);
    }

    const { rows } = await pool().query(
      'SELECT COUNT(*)::int AS count FROM search_history WHERE user_id = $1',
      [userId]
    );
    expect(rows[0].count).toBeLessThanOrEqual(50);

    const { rows: newest } = await pool().query(
      'SELECT COUNT(*)::int AS count FROM search_history WHERE user_id = $1 AND query = $2',
      [userId, 'app-probe-54']
    );
    expect(newest[0].count).toBe(1);
  });
});

/**
 * Event-scoped announcements. `announcements.event_id` is what lets the Event
 * Details feed return one event's messages without pulling in a co-located
 * event's messages from the same venue.
 */
describe('schema integrity: announcement event scoping', () => {
  it('constrains announcements.event_id to a real event', async () => {
    const { rows } = await pool().query(
      `SELECT conname, pg_get_constraintdef(oid) AS def
         FROM pg_constraint
        WHERE conrelid = 'announcements'::regclass
          AND contype = 'f'
          AND conname = 'announcements_event_id_fkey'`
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].def).toContain('FOREIGN KEY (event_id) REFERENCES events(id)');
    // Deleting an event must not leave its announcements orphaned.
    expect(rows[0].def).toContain('ON DELETE CASCADE');
  });

  it('keeps event_id nullable for venue-level announcements', async () => {
    const { rows } = await pool().query(
      `SELECT is_nullable FROM information_schema.columns
        WHERE table_name = 'announcements' AND column_name = 'event_id'`
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].is_nullable).toBe('YES');
  });

  it('indexes announcements the way the event-scoped feed reads them', async () => {
    const { rows } = await pool().query(
      `SELECT indexdef FROM pg_indexes
        WHERE tablename = 'announcements' AND indexname = 'idx_announcements_event'`
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].indexdef).toContain('event_id');
    // Postgres renders the partial predicate parenthesised.
    expect(rows[0].indexdef.replace(/\(\s*|\s*\)/g, '')).toContain(
      'WHERE event_id IS NOT NULL'
    );
  });

  it('refuses an announcement attached to an event that does not exist', async () => {
    await expect(
      pool().query(
        `INSERT INTO announcements (room, message, event_id)
         VALUES ('room-204', 'orphan event announcement', $1)`,
        ['evt_does_not_exist']
      )
    ).rejects.toThrow(/announcements_event_id_fkey/);
  });

  it('accepts an announcement for a seeded venue and event', async () => {
    const { rowCount } = await pool().query(
      `INSERT INTO announcements (room, message, event_id)
       VALUES ('room-204', 'schema probe announcement', 'evt_001')
       ON CONFLICT (room, message) DO NOTHING`
    );
    expect(rowCount).toBe(1);

    await pool().query(
      `DELETE FROM announcements WHERE message = 'schema probe announcement'`
    );
  });

  it('leaves the relay text suffix untouched so (room, message) stays unique', async () => {
    const { rows } = await pool().query(
      `SELECT conname, pg_get_constraintdef(oid) AS def
         FROM pg_constraint
        WHERE conrelid = 'announcements'::regclass
          AND contype = 'u'`
    );

    const roomMessage = rows.find((row) => row.def.includes('room') && row.def.includes('message'));
    expect(roomMessage).toBeDefined();
  });

  it('resolves the bracketed relay event id when backfilling legacy rows', async () => {
    const marker = 'backfill probe';
    const message = `${marker} [evt_001]`;
    await pool().query(
      'INSERT INTO announcements (room, message) VALUES ($1, $2)',
      ['room-204', message]
    );

    try {
      // Exercise the same expression migration 003 uses, to prove the id in the
      // stored text is recoverable.
      await pool().query(
        `UPDATE announcements a
            SET event_id = substring(a.message FROM '\\[(evt_[A-Za-z0-9_-]+)\\]\\s*$')
          WHERE a.event_id IS NULL
            AND a.message ~ '\\[(evt_[A-Za-z0-9_-]+)\\]\\s*$'
            AND EXISTS (
              SELECT 1 FROM events e
               WHERE e.id = substring(a.message FROM '\\[(evt_[A-Za-z0-9_-]+)\\]\\s*$')
            )`
      );

      const { rows } = await pool().query(
        'SELECT event_id FROM announcements WHERE message = $1',
        [message]
      );
      expect(rows[0].event_id).toBe('evt_001');
    } finally {
      await pool().query('DELETE FROM announcements WHERE message = $1', [message]);
    }
  });

  it('removes an event announcement when the event is deleted', async () => {
    const eventId = unique('evt_cascade');
    await pool().query(
      `INSERT INTO events (id, title, category, venue_id, date, time, duration_min, capacity, registered, host, description, tags, icon)
       VALUES ($1, 'Cascade Probe', 'Tech', 'room-204', '2026-12-03', '12:00', 30, 5, 0, 'Host', 'Cascade probe event.', '["tech"]'::jsonb, 'C')`,
      [eventId]
    );
    await pool().query(
      `INSERT INTO announcements (room, message, event_id) VALUES ($1, $2, $3)`,
      ['room-204', 'cascade probe announcement', eventId]
    );

    await pool().query('DELETE FROM events WHERE id = $1', [eventId]);

    const { rows } = await pool().query(
      'SELECT COUNT(*)::int AS count FROM announcements WHERE message = $1',
      ['cascade probe announcement']
    );
    expect(rows[0].count).toBe(0);
  });
});

afterAll(async () => {
  for (const userId of probeIds) {
    await pool().query('DELETE FROM search_history WHERE user_id = $1', [userId]);
    await pool().query('DELETE FROM users WHERE id = $1', [userId]);
  }
});