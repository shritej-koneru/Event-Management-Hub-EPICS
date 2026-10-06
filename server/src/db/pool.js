import pg from 'pg';
import { config, resolveDatabaseUrl } from '../config/index.js';

/**
 * Type parsers keep the JSON payloads byte-compatible with the Android models:
 *  - DATE (1082) stays the raw `YYYY-MM-DD` string instead of becoming a JS Date.
 *  - INT8 (20) becomes a number so ids and COUNTs serialize as JSON numbers,
 *    which is what the Kotlin `Long`/`Int` fields expect.
 */
pg.types.setTypeParser(1082, (value) => value);
pg.types.setTypeParser(20, (value) => (value === null ? null : Number(value)));

let pool = null;
let activeUrl = null;

/**
 * Creates (or re-points) the shared connection pool.
 * Tests call this with an explicit URL to bypass DATABASE_URL.
 */
export function initPool(url = resolveDatabaseUrl()) {
  if (pool && activeUrl === url) return pool;
  if (pool) pool.end().catch(() => {});
  pool = new pg.Pool({
    connectionString: url,
    max: config.isTest ? 5 : 10,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000
  });
  activeUrl = url;
  pool.on('error', (err) => {
    console.error('[db] idle client error:', err.message);
  });
  return pool;
}

export function getPool() {
  if (!pool) initPool();
  return pool;
}

export async function query(text, params) {
  return getPool().query(text, params);
}

/** Verifies connectivity with a trivial round-trip. */
export async function healthcheck() {
  const { rows } = await getPool().query('SELECT 1 AS ok');
  return rows[0].ok === 1;
}

export async function closePool() {
  if (!pool) return;
  const current = pool;
  pool = null;
  activeUrl = null;
  await current.end();
}

/**
 * Drops a database by name, if it exists.
 *
 * Connects to the `postgres` maintenance database rather than the target, so the
 * caller cannot be connected to the database it is deleting. Only ever called by
 * scratch-database tooling; the application itself never drops a database.
 *
 * @param {string} name database to drop
 * @param {string} adminUrl connection string pointing at the maintenance DB
 */
export async function dropDatabaseIfExists(name, adminUrl) {
  const client = new pg.Client({ connectionString: adminUrl });
  try {
    await client.connect();
    // Identifier cannot be parameterised; escapeIdentifier keeps the name a
    // single quoted token rather than allowing injection.
    await client.query(`DROP DATABASE IF EXISTS ${client.escapeIdentifier(name)}`);
  } finally {
    await client.end().catch(() => {});
  }
}

/**
 * Creates a database by name, if it is missing.
 *
 * @param {string} name database to create
 * @param {string} adminUrl connection string pointing at the maintenance DB
 */
export async function createDatabaseIfNotExists(name, adminUrl) {
  const client = new pg.Client({ connectionString: adminUrl });
  try {
    await client.connect();
    const { rows } = await client.query('SELECT 1 FROM pg_database WHERE datname = $1', [name]);
    if (rows.length > 0) return false;
    await client.query(`CREATE DATABASE ${client.escapeIdentifier(name)}`);
    return true;
  } finally {
    await client.end().catch(() => {});
  }
}