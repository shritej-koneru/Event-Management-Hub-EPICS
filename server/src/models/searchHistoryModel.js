import { getPool } from '../db/pool.js';

/** Most recent distinct queries for a user, newest first. */
export async function recentQueries(userId, limit = 5) {
  const { rows } = await getPool().query(
    `SELECT DISTINCT ON (lower(query)) query
     FROM search_history
     WHERE user_id = $1
     ORDER BY lower(query), at DESC
     LIMIT $2`,
    [userId, limit]
  );
  return rows.map((row) => row.query);
}

/**
 * Records a search, then prunes the user's history to the newest rows.
 *
 * The insert and the prune run as separate statements in one transaction: a
 * data-modifying CTE sharing a snapshot with the prune would NOT see the row it
 * just inserted, which lets the table settle one row above the limit instead of
 * at it. Separate statements see each other's writes.
 */
export async function recordSearch(userId, query) {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    await client.query(
      'INSERT INTO search_history (user_id, query) VALUES ($1, $2)',
      [userId, query]
    );
    await client.query('SELECT prune_search_history(50)');
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}