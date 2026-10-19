import { getPool } from '../db/pool.js';
import { VENUES, MAP_POSITIONS, isValidVenueId, GRAPH } from '../navigation/venues.js';

/** Rows for GET /navigation/venues, ordered to match the canonical venue list. */
export async function listVenueRows() {
  const { rows } = await getPool().query('SELECT id, name FROM venues ORDER BY name ASC');
  return rows;
}

/**
 * Map rows for GET /navigation/map: id, name and fractional canvas position.
 * Coordinates are seeded from MAP_POSITIONS and CHECKed to [0, 1] by 005.
 */
export async function listVenueMapRows() {
  const { rows } = await getPool().query(
    'SELECT id, name, map_x, map_y FROM venues ORDER BY name ASC'
  );
  return rows;
}

/** One map row by node id (null when the venue is unknown). */
export async function getVenueMapRow(venueId) {
  const { rows } = await getPool().query(
    'SELECT id, name, map_x, map_y FROM venues WHERE id = $1',
    [venueId]
  );
  return rows[0] ?? null;
}

/** Stored graph edges, one row per undirected edge (from_id < to_id). */
export async function listVenueEdgeRows() {
  const { rows } = await getPool().query(
    'SELECT from_id, to_id, distance_m FROM venue_edges ORDER BY from_id, to_id'
  );
  return rows;
}

/**
 * Inserts the canonical venue catalogue. Callers must use ON CONFLICT so the
 * seed is idempotent and never rewrites ids the Android map depends on.
 * Map coordinates ride along so every boot re-syncs the stored layout with
 * the canonical MAP_POSITIONS.
 */
export async function upsertVenues() {
  const pool = getPool();
  let inserted = 0;
  for (const venue of VENUES) {
    const pos = MAP_POSITIONS[venue.id];
    const { rowCount } = await pool.query(
      `INSERT INTO venues (id, name, map_x, map_y) VALUES ($1, $2, $3, $4)
       ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
                                      map_x = EXCLUDED.map_x,
                                      map_y = EXCLUDED.map_y`,
      [venue.id, venue.name, pos.x, pos.y]
    );
    inserted += rowCount ?? 0;
  }
  return inserted;
}

/**
 * Mirrors GRAPH into venue_edges (one row per undirected edge). Idempotent,
 * so the seed keeps the stored copy in lock-step with the canonical graph.
 */
export async function upsertVenueEdges() {
  const pool = getPool();
  let synced = 0;
  for (const [from, edges] of Object.entries(GRAPH)) {
    for (const [to, distance] of Object.entries(edges)) {
      if (from >= to) continue; // store each undirected edge once
      const { rowCount } = await pool.query(
        `INSERT INTO venue_edges (from_id, to_id, distance_m) VALUES ($1, $2, $3)
         ON CONFLICT (from_id, to_id) DO UPDATE SET distance_m = EXCLUDED.distance_m`,
        [from, to, distance]
      );
      synced += rowCount ?? 0;
    }
  }
  return synced;
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