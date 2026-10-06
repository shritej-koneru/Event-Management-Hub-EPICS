import { existsSync } from 'node:fs';
import path from 'node:path';
import pg from 'pg';
import { config } from '../config/index.js';

/**
 * Bundled local PostgreSQL cluster for development and tests.
 *
 * Uses the real PostgreSQL binaries (via the `embedded-postgres` devDependency)
 * so application code, migrations and queries are 100% PostgreSQL — the only
 * difference from production is where the connection string comes from.
 * No admin rights and no external services are required.
 *
 * `embedded-postgres` is a devDependency, so it is imported lazily and is never
 * reached in production (where DATABASE_URL is mandatory).
 */

let instance = null;
let running = false;
/** True only when this process started the cluster, so we never stop someone else's. */
let owned = false;

function urlFor(database) {
  const { user, password, port } = config.embeddedPg;
  const auth = `${encodeURIComponent(user)}:${encodeURIComponent(password)}`;
  return `postgres://${auth}@127.0.0.1:${port}/${encodeURIComponent(database)}`;
}

/** Connection string for the application database. */
function connectionUrl() {
  return urlFor(config.embeddedPg.database);
}

/** Connection string for the always-present `postgres` maintenance database. */
function maintenanceUrl() {
  return urlFor('postgres');
}

/** Runs `fn` with a short-lived client on the maintenance database. */
async function withMaintenanceClient(fn) {
  const client = new pg.Client({
    connectionString: maintenanceUrl(),
    connectionTimeoutMillis: 10_000
  });
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end().catch(() => {});
  }
}

/** @returns {Promise<boolean>} true when the cluster is already accepting connections. */
function isListening() {
  return withMaintenanceClient(async (client) => {
    await client.query('SELECT 1');
    return true;
  }).catch(() => false);
}

export async function startEmbeddedPostgres() {
  if (running) return connectionUrl();

  // A cluster from a previous run may still be listening (for example the dev
  // server is already running in another terminal). Reuse it rather than
  // failing to boot, and never stop a process this process did not start.
  if (await isListening()) {
    running = true;
    owned = false;
    console.log(`[db] reusing local PostgreSQL already listening on 127.0.0.1:${config.embeddedPg.port}`);
    await ensureDatabaseExists();
    return connectionUrl();
  }

  const { default: EmbeddedPostgres } = await import('embedded-postgres');
  const { dir, user, password, port } = config.embeddedPg;

  const needsInit = !existsSync(path.join(dir, 'PG_VERSION'));
  instance = new EmbeddedPostgres({
    databaseDir: dir,
    user,
    password,
    port,
    persistent: true,
    // Windows initdb defaults to the system ANSI code page (WIN1252 here), which
    // cannot store the 4-byte emoji used in event icons. Force UTF-8 so the
    // local cluster matches a normal production PostgreSQL.
    initdbFlags: ['--encoding=UTF8']
  });

  if (needsInit) {
    console.log(`[db] initialising local PostgreSQL cluster in ${dir}`);
    await instance.initialise();
  }

  try {
    await instance.start();
  } catch (err) {
    const detail = err?.message ?? String(err);
    throw new Error(
      `Could not start the local PostgreSQL cluster in "${dir}": ${detail}\n` +
        'Close any other process using it, then retry. If the cluster is stale, ' +
        `delete the "${dir}" directory and re-run.`,
      { cause: err }
    );
  }

  running = true;
  owned = true;
  await ensureDatabaseExists();

  console.log(`[db] local PostgreSQL listening on 127.0.0.1:${port}`);
  return connectionUrl();
}

/**
 * Verifies the cluster encoding is UTF-8 and creates the application database
 * when missing.
 *
 * Existence is checked up-front rather than by catching `duplicate_database`,
 * because `embedded-postgres`' own createDatabase leaks its client on failure,
 * which surfaces as a confusing ECONNRESET during shutdown. This also works
 * when the cluster is being reused rather than owned by this process.
 *
 * @returns {Promise<boolean>} true when the database was created
 */
async function ensureDatabaseExists() {
  const database = config.embeddedPg.database;

  return withMaintenanceClient(async (client) => {
    const { rows: encodings } = await client.query(
      "SELECT pg_encoding_to_char(encoding) AS enc FROM pg_database WHERE datname = current_database()"
    );
    const encoding = encodings[0]?.enc;
    if (encoding && encoding.toUpperCase() !== 'UTF8') {
      throw new Error(
        `The local cluster in "${config.embeddedPg.dir}" was created with ${encoding} encoding. ` +
          'Delete that directory and re-run so it is recreated as UTF8 (event icons contain emoji).'
      );
    }

    const { rows } = await client.query('SELECT 1 FROM pg_database WHERE datname = $1', [database]);
    if (rows.length > 0) return false;

    await client.query(`CREATE DATABASE ${client.escapeIdentifier(database)}`);
    console.log(`[db] created database "${database}"`);
    return true;
  });
}

export async function stopEmbeddedPostgres() {
  running = false;
  if (!instance || !owned) {
    instance = null;
    owned = false;
    return;
  }
  try {
    await instance.stop();
  } catch (err) {
    console.error('[db] failed to stop local PostgreSQL:', err?.message ?? String(err));
  } finally {
    instance = null;
    owned = false;
  }
}

export function isEmbeddedPostgresRunning() {
  return running;
}

export { connectionUrl as embeddedConnectionUrl };