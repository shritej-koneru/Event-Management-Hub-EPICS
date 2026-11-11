import { getPool } from '../db/pool.js';

/**
 * Maps a joined events+venues row to the canonical Event JSON shape from
 * contract/API-CONTRACT.md §2. Keys and casing here are what the Kotlin
 * `Event` data class deserializes, so they must not drift.
 */
export function rowToEvent(row) {
  if (!row) return null;
  return {
    id: row.id,
    title: row.title,
    category: row.category,
    venue: row.venue_name,
    venueId: row.venue_id,
    date: row.date,
    time: row.time,
    durationMin: row.duration_min,
    capacity: row.capacity,
    registered: row.registered,
    host: row.host,
    description: row.description,
    tags: Array.isArray(row.tags) ? row.tags : [],
    icon: row.icon,
    // Typed and explicit. null / an absent key means UNKNOWN, never "no" and never inferred from the description.
    price: rowToPrice(row),
    accessibility: row.accessibility && typeof row.accessibility === 'object' ? row.accessibility : {}
  };
}

/** `null` (unknown) | `{ type: 'free' }` | `{ type: 'paid', amountCents, currency }`. */
function rowToPrice(row) {
  if (row.price_type === 'free') return { type: 'free' };
  if (row.price_type === 'paid') {
    return { type: 'paid', amountCents: row.price_amount_cents, currency: row.price_currency };
  }
  return null;
}

/** Splits the API price value into the three columns. Unknown (undefined/null) stores NULLs. */
export function priceToColumns(price) {
  if (!price) return { type: null, amount: null, currency: null };
  if (price.type === 'free') return { type: 'free', amount: null, currency: null };
  return { type: 'paid', amount: price.amountCents, currency: price.currency };
}

const SELECT_EVENT = `
  SELECT e.*, v.name AS venue_name
  FROM events e
  JOIN venues v ON v.id = e.venue_id
`;

/**
 * Builds the shared WHERE clause for list queries.
 *
 * Filtering happens in SQL so it scales past the in-memory approach, and so
 * pagination can be applied to the filtered set. Every value is a bound
 * parameter - no user input is ever interpolated.
 *
 * @param {{category?: string, date?: string, venue?: string, q?: string}} filters
 * @returns {{clause: string, values: any[]}}
 */
function buildEventFilters({ category, date, venue, q }) {
  const conditions = [];
  const values = [];

  if (category) {
    values.push(category.toLowerCase());
    conditions.push(`lower(e.category) = $${values.length}`);
  }

  if (date) {
    values.push(date);
    conditions.push(`e.date = $${values.length}::date`);
  }

  // Accepts a canonical venue id ("room-204") or display name ("Room 204").
  if (venue) {
    values.push(venue.toLowerCase());
    conditions.push(
      `(lower(e.venue_id) = $${values.length} OR lower(v.name) = $${values.length})`
    );
  }

  if (q) {
    values.push(`%${q.toLowerCase()}%`);
    const p = `$${values.length}`;
    conditions.push(
      `(lower(e.title) LIKE ${p}
        OR lower(e.description) LIKE ${p}
        OR lower(e.host) LIKE ${p}
        OR lower(e.category) LIKE ${p}
        OR lower(v.name) LIKE ${p}
        OR EXISTS (
             SELECT 1 FROM jsonb_array_elements_text(e.tags) AS tag
             WHERE lower(tag) LIKE ${p}
           ))`
    );
  }

  return {
    clause: conditions.length ? ` WHERE ${conditions.join(' AND ')}` : '',
    values
  };
}

const ORDER_EVENT = ' ORDER BY e.date ASC, e.time ASC, e.id ASC';

/**
 * Lists events, optionally filtered and paginated.
 *
 * When `limit` is omitted the whole filtered set is returned, which keeps the
 * internal callers (AI search, recommendations, profile building) unchanged.
 *
 * @returns {Promise<{events: object[], total: number}>} `total` is the number of
 *   rows matching the filters before pagination.
 */
export async function listEvents(filters = {}) {
  const { clause, values } = buildEventFilters(filters);

  const countQuery = `
    SELECT COUNT(*)::int AS count
    FROM events e
    JOIN venues v ON v.id = e.venue_id${clause}
  `;
  const { rows: countRows } = await getPool().query(countQuery, values);
  const total = countRows[0].count;

  // No limit => return everything the filters match.
  if (filters.limit === undefined || filters.limit === null) {
    const { rows } = await getPool().query(`${SELECT_EVENT}${clause}${ORDER_EVENT}`, values);
    return { events: rows.map(rowToEvent), total: rows.length };
  }

  const limitValues = [...values, filters.limit, filters.offset ?? 0];
  const { rows } = await getPool().query(
    `${SELECT_EVENT}${clause}${ORDER_EVENT} LIMIT $${limitValues.length - 1} OFFSET $${limitValues.length}`,
    limitValues
  );

  return { events: rows.map(rowToEvent), total };
}

export async function findEventById(id) {
  const { rows } = await getPool().query(`${SELECT_EVENT} WHERE e.id = $1`, [id]);
  return rowToEvent(rows[0]);
}

export async function findEventsByIds(ids) {
  if (!ids.length) return [];
  const { rows } = await getPool().query(`${SELECT_EVENT} WHERE e.id = ANY($1::text[])`, [ids]);
  return rows.map(rowToEvent);
}

/**
 * Inserts an event. `venueId` must already be a validated canonical graph node.
 * @returns the persisted Event object
 */
export async function insertEvent(event) {
  const pool = getPool();
  const { rows } = await pool.query(
    `INSERT INTO events
       (id, title, category, venue_id, date, time, duration_min,
        capacity, registered, host, description, tags, icon, created_by,
        price_type, price_amount_cents, price_currency, accessibility)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb, $13, $14, $15, $16, $17, $18::jsonb)
     RETURNING id`,
    [
      event.id,
      event.title,
      event.category,
      event.venueId,
      event.date,
      event.time,
      event.durationMin,
      event.capacity,
      event.registered,
      event.host,
      event.description,
      JSON.stringify(event.tags),
      event.icon,
      event.createdBy ?? null,
      priceToColumns(event.price).type,
      priceToColumns(event.price).amount,
      priceToColumns(event.price).currency,
      JSON.stringify(event.accessibility ?? {})
    ]
  );
  return findEventById(rows[0].id);
}

/** Owner of an event (created_by is not part of the public Event JSON). */
export async function findEventOwner(id) {
  const { rows } = await getPool().query('SELECT created_by FROM events WHERE id = $1', [id]);
  return rows[0] ? { exists: true, createdBy: rows[0].created_by } : { exists: false, createdBy: null };
}

/** Id of an event with the same title (case-insensitive), date and venue, if any. */
export async function findDuplicateEvent({ title, date, venueId, excludeId = null }) {
  const { rows } = await getPool().query(
    `SELECT id FROM events
      WHERE lower(title) = lower($1) AND date = $2::date AND venue_id = $3
        AND ($4::text IS NULL OR id <> $4)
      LIMIT 1`,
    [title, date, venueId, excludeId]
  );
  return rows[0]?.id ?? null;
}

const UPDATABLE = {
  title: 'title', category: 'category', venueId: 'venue_id', date: 'date', time: 'time',
  durationMin: 'duration_min', capacity: 'capacity', registered: 'registered',
  host: 'host', description: 'description', icon: 'icon'
};

/** Partial update; only keys present in `patch` change. Returns the Event or null. */
export async function updateEventRow(id, patch) {
  const sets = [];
  const values = [id];
  for (const [key, column] of Object.entries(UPDATABLE)) {
    if (patch[key] === undefined) continue;
    values.push(patch[key]);
    sets.push(`${column} = $${values.length}`);
  }
  if (patch.tags !== undefined) {
    values.push(JSON.stringify(patch.tags));
    sets.push(`tags = $${values.length}::jsonb`);
  }
  // `price: null` clears it back to unknown; `accessibility: {}` clears every feature back to unknown.
  if (patch.price !== undefined) {
    const cols = priceToColumns(patch.price);
    values.push(cols.type, cols.amount, cols.currency);
    sets.push(`price_type = $${values.length - 2}`, `price_amount_cents = $${values.length - 1}`, `price_currency = $${values.length}`);
  }
  if (patch.accessibility !== undefined) {
    values.push(JSON.stringify(patch.accessibility));
    sets.push(`accessibility = $${values.length}::jsonb`);
  }
  if (!sets.length) return findEventById(id);
  const { rowCount } = await getPool().query(`UPDATE events SET ${sets.join(', ')} WHERE id = $1`, values);
  return rowCount ? findEventById(id) : null;
}

export async function deleteEventRow(id) {
  const { rowCount } = await getPool().query('DELETE FROM events WHERE id = $1', [id]);
  return rowCount > 0;
}

export async function countEvents() {
  const { rows } = await getPool().query('SELECT COUNT(*)::int AS count FROM events');
  return rows[0].count;
}

/**
 * Number of events sharing a category. Used by the Event Details category-mix
 * chart so the figures come from the catalogue rather than being invented.
 */
export async function countEventsByCategory(category) {
  const { rows } = await getPool().query(
    'SELECT COUNT(*)::int AS count FROM events WHERE lower(category) = lower($1)',
    [category]
  );
  return rows[0].count;
}
/* -------------------------------------------------------------------------- */
/* Manager console                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Events with ownership + bookmark count, date ascending.
 * `createdBy` restricts to one owner; null returns every event.
 */
export async function listManagedEvents({ createdBy = null } = {}) {
  const { rows } = await getPool().query(
    `SELECT e.*, v.name AS venue_name,
            (SELECT COUNT(*)::int FROM bookmarks b WHERE b.event_id = e.id) AS bookmark_count
       FROM events e
       JOIN venues v ON v.id = e.venue_id
      WHERE ($1::text IS NULL OR e.created_by = $1)
      ORDER BY e.date ASC, e.time ASC, e.id ASC`,
    [createdBy]
  );
  return rows.map((row) => ({
    ...rowToEvent(row),
    createdBy: row.created_by ?? null,
    bookmarkCount: row.bookmark_count
  }));
}

/** Event counts + bookmark total for the manager overview. */
export async function eventOverviewCounts({ createdBy = null, today }) {
  const { rows } = await getPool().query(
    `SELECT COUNT(*)::int AS total,
            COUNT(*) FILTER (WHERE e.date >= $2::date)::int AS upcoming,
            COUNT(*) FILTER (WHERE e.date <  $2::date)::int AS past,
            COALESCE(SUM((SELECT COUNT(*) FROM bookmarks b WHERE b.event_id = e.id)), 0)::int AS bookmarks
       FROM events e
      WHERE ($1::text IS NULL OR e.created_by = $1)`,
    [createdBy, today]
  );
  return rows[0];
}
