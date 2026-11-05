import { getPool } from '../db/pool.js';
import { VENUES, MAP_POSITIONS, GRAPH } from '../navigation/venues.js';

/** Map columns incl. the 1.5.0 geo fields (migration 008). */
const MAP_COLUMNS = 'id, name, map_x, map_y, lat, lng, kind, description, geo_placed';

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
    `SELECT ${MAP_COLUMNS} FROM venues ORDER BY name ASC`
  );
  return rows;
}

/** One map row by node id (null when the venue is unknown). */
export async function getVenueMapRow(venueId) {
  const { rows } = await getPool().query(`SELECT ${MAP_COLUMNS} FROM venues WHERE id = $1`, [venueId]);
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
                                      map_y = EXCLUDED.map_y,
                                      lat = NULL, lng = NULL, geo_placed = false`,
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

/* -------------------------------------------------------------------------- */
/* Manager writes (venues + edges). Callers refresh the registry afterwards.  */
/* -------------------------------------------------------------------------- */

export async function countVenueEdges() {
  const { rows } = await getPool().query('SELECT COUNT(*)::int AS count FROM venue_edges');
  return rows[0].count;
}

/** Case-insensitive name lookup, optionally ignoring one venue id. */
export async function findVenueByName(name, excludeId = null) {
  const { rows } = await getPool().query(
    `SELECT ${MAP_COLUMNS} FROM venues
      WHERE lower(name) = lower($1) AND ($2::text IS NULL OR id <> $2)
      LIMIT 1`,
    [name, excludeId]
  );
  return rows[0] ?? null;
}

export async function insertVenue({ id, name, x, y, lat = null, lng = null, kind = '', description = '', geoPlaced = false }) {
  const { rows } = await getPool().query(
    `INSERT INTO venues (id, name, map_x, map_y, lat, lng, kind, description, geo_placed)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING ${MAP_COLUMNS}`,
    [id, name, x, y, lat, lng, kind, description, geoPlaced]
  );
  return rows[0];
}

/**
 * Partial update. `position` is either omitted (keep), `{x, y}` (clears the
 * stored lat/lng, geo_placed=false) or `{x, y, lat, lng}` (geo_placed=true).
 * Returns the row or null when unknown.
 */
export async function updateVenueRow(id, { name, kind, description, position }) {
  const hasPos = position !== undefined;
  const { rows } = await getPool().query(
    `UPDATE venues
        SET name = COALESCE($2, name),
            kind = COALESCE($3, kind),
            description = COALESCE($4, description),
            map_x = CASE WHEN $5::boolean THEN $6::double precision ELSE map_x END,
            map_y = CASE WHEN $5::boolean THEN $7::double precision ELSE map_y END,
            lat = CASE WHEN $5::boolean THEN $8::double precision ELSE lat END,
            lng = CASE WHEN $5::boolean THEN $9::double precision ELSE lng END,
            geo_placed = CASE WHEN $5::boolean THEN $10::boolean ELSE geo_placed END
      WHERE id = $1
      RETURNING ${MAP_COLUMNS}`,
    [
      id,
      name ?? null,
      kind ?? null,
      description ?? null,
      hasPos,
      position?.x ?? null,
      position?.y ?? null,
      position?.lat ?? null,
      position?.lng ?? null,
      position?.lat != null
    ]
  );
  return rows[0] ?? null;
}

export async function countEventsAtVenue(id) {
  const { rows } = await getPool().query(
    'SELECT COUNT(*)::int AS count FROM events WHERE venue_id = $1',
    [id]
  );
  return rows[0].count;
}

/** Deletes a venue (its edges cascade). */
export async function deleteVenueRow(id) {
  const { rowCount } = await getPool().query('DELETE FROM venues WHERE id = $1', [id]);
  return rowCount > 0;
}

/** Undirected edge key: from_id < to_id. */
const ordered = (a, b) => (a < b ? [a, b] : [b, a]);

export async function findEdgeRow(a, b) {
  const [from, to] = ordered(a, b);
  const { rows } = await getPool().query(
    'SELECT from_id, to_id, distance_m FROM venue_edges WHERE from_id = $1 AND to_id = $2',
    [from, to]
  );
  return rows[0] ?? null;
}

export async function insertEdgeRow(a, b, distanceM) {
  const [from, to] = ordered(a, b);
  const { rows } = await getPool().query(
    `INSERT INTO venue_edges (from_id, to_id, distance_m) VALUES ($1, $2, $3)
     RETURNING from_id, to_id, distance_m`,
    [from, to, distanceM]
  );
  return rows[0];
}

export async function updateEdgeRow(a, b, distanceM) {
  const [from, to] = ordered(a, b);
  const { rows } = await getPool().query(
    `UPDATE venue_edges SET distance_m = $3 WHERE from_id = $1 AND to_id = $2
     RETURNING from_id, to_id, distance_m`,
    [from, to, distanceM]
  );
  return rows[0] ?? null;
}

export async function deleteEdgeRow(a, b) {
  const [from, to] = ordered(a, b);
  const { rowCount } = await getPool().query(
    'DELETE FROM venue_edges WHERE from_id = $1 AND to_id = $2',
    [from, to]
  );
  return rowCount > 0;
}
