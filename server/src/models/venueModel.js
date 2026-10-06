import { getPool } from '../db/pool.js';
import { VENUES, isValidVenueId } from '../navigation/venues.js';

/** Rows for GET /navigation/venues, ordered to match the canonical venue list. */
export async function listVenueRows() {
  const { rows } = await getPool().query('SELECT id, name FROM venues ORDER BY name ASC');
  return rows;
}

/**
 * Inserts the canonical venue catalogue. Callers must use ON CONFLICT so the
 * seed is idempotent and never rewrites ids the Android map depends on.
 */
export async function upsertVenues() {
  const pool = getPool();
  let inserted = 0;
  for (const venue of VENUES) {
    const { rowCount } = await pool.query(
      `INSERT INTO venues (id, name) VALUES ($1, $2)
       ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name`,
      [venue.id, venue.name]
    );
    inserted += rowCount ?? 0;
  }
  return inserted;
}

export async function countVenues() {
  const { rows } = await getPool().query('SELECT COUNT(*)::int AS count FROM venues');
  return rows[0].count;
}

/**
 * Guards referential integrity: an event may only reference a node that also
 * exists in the navigation graph.
 */
export function assertVenueIsNavigable(venueId) {
  if (!isValidVenueId(venueId)) {
    throw new Error(`Venue "${venueId}" is not a navigation graph node`);
  }
  return venueId;
}