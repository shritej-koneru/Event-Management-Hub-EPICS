import { getPool } from '../db/pool.js';

const MAX_RETURNED = 20;

/**
 * Persists an announcement idempotently.
 *
 * The `announcements_room_message_key` UNIQUE constraint is the single source of
 * duplicate protection — `ON CONFLICT DO NOTHING` turns a repeat insert into
 * a no-op rather than an error, so relaying the same (room, message) twice is
 * impossible no matter how many times it is triggered.
 *
 * @param {string} room   bare venue id ("room-204")
 * @param {string} message stored text; the relay embeds the event id
 * @param {string|null} [eventId] event this announcement belongs to. Null for
 *   venue-level notices that are not tied to one event.
 * @returns {Promise<{ created: boolean, announcement: object }>}
 */
export async function recordAnnouncement(room, message, eventId = null) {
  const pool = getPool();
  const { rows, rowCount } = await pool.query(
    `INSERT INTO announcements (room, message, event_id)
     VALUES ($1, $2, $3)
     ON CONFLICT (room, message) DO NOTHING
     RETURNING id, room, message, event_id, at`,
    [room, message, eventId]
  );

  if (rowCount > 0) {
    return { created: true, announcement: serialize(rows[0]) };
  }

  // The row already exists, so uniqueness is unaffected. But fill in a missing
  // event_id: a row written before migration 003 - or a seed row whose text
  // carries no "[evt_...]" suffix - would otherwise stay permanently
  // venue-level, because the migration backfill cannot infer an association
  // from text it was never given. COALESCE preserves any known association.
  const existing = await pool.query(
    `UPDATE announcements
        SET event_id = COALESCE(event_id, $3)
      WHERE room = $1 AND message = $2
      RETURNING id, room, message, event_id, at`,
    [room, message, eventId]
  );
  return { created: false, announcement: serialize(existing.rows[0]) };
}

function serialize(row) {
  return {
    id: row.id,
    room: stripRoomPrefix(row.room),
    message: row.message,
    at: row.at instanceof Date ? row.at.toISOString() : row.at,
    // Only present when the announcement is tied to an event. Keeping it absent
    // for venue-level rows means existing clients (Kotlin `Announcement`, which
    // ignores unknown keys) are unaffected.
    ...(row.event_id ? { eventId: row.event_id } : {})
  };
}

/** `announce:main-hall` -> `main-hall` (the DB stores bare venue ids). */
export function stripRoomPrefix(room) {
  return typeof room === 'string' ? room.replace(/^announce:/, '') : room;
}

/**
 * Latest announcements, newest first, as persisted in the DB.
 *
 * @param {number} [limit]
 * @param {{eventId?: string|null}} [scope] when `eventId` is set the result is
 *   restricted to announcements recorded for THAT event. This is what keeps two
 *   events at one venue from sharing a feed.
 */
export async function listRecentAnnouncements(limit = MAX_RETURNED, { eventId = null } = {}) {
  const pool = getPool();
  const capped = Math.min(limit, MAX_RETURNED);

  if (eventId) {
    const { rows } = await pool.query(
      `SELECT id, room, message, event_id, at
         FROM announcements
        WHERE event_id = $1
        ORDER BY id DESC
        LIMIT $2`,
      [eventId, capped]
    );
    return rows.map(serialize);
  }

  const { rows } = await pool.query(
    `SELECT id, room, message, event_id, at
       FROM announcements
      ORDER BY id DESC
      LIMIT $1`,
    [capped]
  );
  return rows.map(serialize);
}

/** Announcements recorded for one event. Used by the Event Details feed. */
export async function countAnnouncementsForEvent(eventId) {
  const { rows } = await getPool().query(
    'SELECT COUNT(*)::int AS count FROM announcements WHERE event_id = $1',
    [eventId]
  );
  return rows[0].count;
}

/**
 * Announcement counts for one event grouped by calendar day, oldest first.
 *
 * Real data only: days with no announcements are absent rather than
 * zero-filled, so the caller can decide whether there is enough to chart.
 */
export async function announcementTrendForEvent(eventId) {
  const { rows } = await getPool().query(
    `SELECT to_char(at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
            COUNT(*)::int AS count
       FROM announcements
      WHERE event_id = $1
      GROUP BY day
      ORDER BY day ASC`,
    [eventId]
  );
  return rows.map((row) => ({ label: row.day, value: row.count }));
}

export async function countAnnouncements() {
  const { rows } = await getPool().query('SELECT COUNT(*)::int AS count FROM announcements');
  return rows[0].count;
}

export { MAX_RETURNED };