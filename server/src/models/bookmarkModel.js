import { getPool } from '../db/pool.js';

/** Event ids a user bookmarked — the main personalization signal. */
export async function listBookmarkIds(userId) {
  const { rows } = await getPool().query(
    'SELECT event_id FROM bookmarks WHERE user_id = $1 ORDER BY created_at DESC',
    [userId]
  );
  return rows.map((row) => row.event_id);
}
/** Idempotent: the (user_id, event_id) primary key blocks duplicates. */
export async function addBookmark(userId, eventId) {
  const { rowCount } = await getPool().query(
    `INSERT INTO bookmarks (user_id, event_id)
     VALUES ($1, $2)
     ON CONFLICT (user_id, event_id) DO NOTHING`,
    [userId, eventId]
  );
  return rowCount > 0;
}

/**
 * Whether THIS user saved the event. Used by the Event Details screen, where
 * the bookmark toggle needs its own state without a second round trip.
 */
export async function isEventBookmarked(userId, eventId) {
  const { rowCount } = await getPool().query(
    'SELECT 1 FROM bookmarks WHERE user_id = $1 AND event_id = $2',
    [userId, eventId]
  );
  return rowCount > 0;
}

/**
 * How many users bookmarked an event — an aggregate popularity signal read from
 * real rows. Never used to claim that a specific user bookmarked something.
 */
export async function countBookmarksForEvent(eventId) {
  const { rows } = await getPool().query(
    'SELECT COUNT(*)::int AS count FROM bookmarks WHERE event_id = $1',
    [eventId]
  );
  return rows[0].count;
}

export async function removeBookmark(userId, eventId) {
  const { rowCount } = await getPool().query(
    'DELETE FROM bookmarks WHERE user_id = $1 AND event_id = $2',
    [userId, eventId]
  );
  return rowCount > 0;
}