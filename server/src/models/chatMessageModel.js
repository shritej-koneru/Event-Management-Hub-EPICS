import { getPool } from '../db/pool.js';

const MAX_RETURNED = 100;
const DEFAULT_RETURNED = 50;

/**
 * Persists one chat message and returns the stored row in wire format.
 *
 * Called by the Socket.IO `chat:message` handler BEFORE any broadcast, so a
 * client never sees a message that is not also in the database.
 *
 * @param {{ author: string, text: string, userId?: string|null,
 *           room?: string|null, bot?: boolean }} input
 * @returns {Promise<object>} `{ id, author, text, room, bot, ts }`
 */
export async function insertChatMessage({ author, text, userId = null, room = null, bot = false }) {
  const pool = getPool();
  const { rows } = await pool.query(
    `INSERT INTO chat_messages (author, user_id, room, text, bot)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING id, author, room, text, bot, at`,
    [author, userId || null, room || null, text, Boolean(bot)]
  );
  return serialize(rows[0]);
}

/**
 * History page for reconnect / cold start.
 *
 * Returns up to `limit` messages in chronological (oldest-first) order.
 * When `room` is set the page is scoped to that bare venue id; when it is
 * omitted every room (including the global `room IS NULL` thread) is returned,
 * which is what the Android single-feed client renders.
 * When `before` is set, only rows strictly older than that timestamp are
 * returned — the cursor for "load more".
 *
 * @param {{ room?: string|null|undefined, before?: string|null, limit?: number }} [opts]
 * @returns {Promise<object[]>}
 */
export async function listRecentChatMessages({ room, before = null, limit = DEFAULT_RETURNED } = {}) {
  const pool = getPool();
  const capped = Math.min(Math.max(Number(limit) || DEFAULT_RETURNED, 1), MAX_RETURNED);
  const scoped = room == null || room === '' ? null : room;

  if (before) {
    const { rows } = await pool.query(
      `SELECT id, author, room, text, bot, at
         FROM chat_messages
        WHERE ($1::text IS NULL OR room = $1::text)
          AND at < $2::timestamptz
        ORDER BY at DESC
        LIMIT $3`,
      [scoped, before, capped]
    );
    return rows.map(serialize).reverse();
  }

  const { rows } = await pool.query(
    `SELECT id, author, room, text, bot, at
       FROM chat_messages
      WHERE ($1::text IS NULL OR room = $1::text)
      ORDER BY at DESC
      LIMIT $2`,
    [scoped, capped]
  );
  return rows.map(serialize).reverse();
}

/** Rows currently stored. Used by tests and operational checks. */
export async function countChatMessages({ room } = {}) {
  const pool = getPool();
  if (room === undefined) {
    const { rows } = await pool.query('SELECT COUNT(*)::int AS count FROM chat_messages');
    return rows[0].count;
  }
  const { rows } = await pool.query(
    'SELECT COUNT(*)::int AS count FROM chat_messages WHERE room IS NOT DISTINCT FROM $1',
    [room || null]
  );
  return rows[0].count;
}

function serialize(row) {
  return {
    id: row.id,
    author: row.author,
    text: row.text,
    room: row.room ?? null,
    bot: Boolean(row.bot),
    ts: row.at instanceof Date ? row.at.toISOString() : row.at
  };
}

export { MAX_RETURNED, DEFAULT_RETURNED };
