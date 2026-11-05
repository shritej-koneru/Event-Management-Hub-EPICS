import { getPool } from '../db/pool.js';

const MAX_RETURNED = 20;

/** Served `room` of a campus-wide announcement (stored as NULL). */
const GLOBAL_ROOM_NAME = 'all';

/**
 * Persists an announcement idempotently.
 *
 * The partial unique index `uq_announcements_auto_room_message` (room, message)
 * WHERE sent_by IS NULL (migration 008) is the single source of
 * duplicate protection for automatic rows — `ON CONFLICT DO NOTHING` turns a repeat insert into
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
     ON CONFLICT (room, message) WHERE sent_by IS NULL DO NOTHING
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
      WHERE room = $1 AND message = $2 AND sent_by IS NULL
      RETURNING id, room, message, event_id, at`,
    [room, message, eventId]
  );
  return { created: false, announcement: serialize(existing.rows[0]) };
}

function serialize(row) {
  return {
    id: row.id,
    room: row.room == null ? GLOBAL_ROOM_NAME : stripRoomPrefix(row.room),
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
/* -------------------------------------------------------------------------- */
/* Manager (organizer/admin) announcements                                     */
/* -------------------------------------------------------------------------- */

function serializeManaged(row) {
  return {
    id: row.id,
    room: row.room ?? GLOBAL_ROOM_NAME,
    message: row.message,
    at: row.at instanceof Date ? row.at.toISOString() : row.at,
    eventId: row.event_id ?? null,
    sentBy: row.sent_by ?? null
  };
}

const MANAGED_COLUMNS = 'id, room, message, event_id, sent_by, at';

/**
 * Inserts a manual announcement. Unlike the idempotent event relay, manager
 * announcements (sent_by NOT NULL) may repeat the same text to the same room:
 * the unique index only covers automatic rows (sent_by IS NULL).
 */
export async function insertManagedAnnouncement({ room, message, eventId = null, sentBy }) {
  const { rows } = await getPool().query(
    `INSERT INTO announcements (room, message, event_id, sent_by)
     VALUES ($1, $2, $3, $4)
     RETURNING ${MANAGED_COLUMNS}`,
    [room === GLOBAL_ROOM_NAME ? null : room, message, eventId, sentBy]
  );
  return serializeManaged(rows[0]);
}

/** Newest first. `sentBy` restricts to one sender; omit for everything. */
export async function listManagedAnnouncements({ sentBy = null, limit = 200 } = {}) {
  const { rows } = await getPool().query(
    `SELECT ${MANAGED_COLUMNS} FROM announcements
      WHERE ($1::text IS NULL OR sent_by = $1)
      ORDER BY id DESC
      LIMIT $2`,
    [sentBy, limit]
  );
  return rows.map(serializeManaged);
}

export async function findManagedAnnouncement(id) {
  const { rows } = await getPool().query(
    `SELECT ${MANAGED_COLUMNS} FROM announcements WHERE id = $1`,
    [id]
  );
  return rows[0] ? serializeManaged(rows[0]) : null;
}

export async function deleteAnnouncementRow(id) {
  const { rowCount } = await getPool().query('DELETE FROM announcements WHERE id = $1', [id]);
  return rowCount > 0;
}

export async function countAnnouncementsBy(sentBy = null) {
  const { rows } = await getPool().query(
    'SELECT COUNT(*)::int AS count FROM announcements WHERE ($1::text IS NULL OR sent_by = $1)',
    [sentBy]
  );
  return rows[0].count;
}

/**
 * Announcements an attendee of one event should see: those recorded for THAT
 * event plus campus-wide (global, room NULL) ones. Never another event's.
 */
export async function listAnnouncementsForEventFeed(eventId, limit = 100) {
  const { rows } = await getPool().query(
    `SELECT a.id, a.room, a.message, a.event_id, a.sent_by, a.at, u.name AS sent_by_name
       FROM announcements a
       LEFT JOIN users u ON u.id = a.sent_by
      WHERE a.event_id = $1 OR (a.room IS NULL AND a.event_id IS NULL)
      ORDER BY a.id DESC
      LIMIT $2`,
    [eventId, limit]
  );
  return rows.map((row) => ({
    ...serializeManaged(row),
    // Automatic relay rows carry a " [evt_…]" dedupe suffix; attendees should not see it.
    message: row.sent_by == null ? row.message.replace(/\s\[[^\]]+\]$/, '') : row.message,
    sentByName: row.sent_by_name ?? null,
    global: row.room == null
  }));
}
