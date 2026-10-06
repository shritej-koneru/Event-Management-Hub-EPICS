import { config, resolveDatabaseUrl } from '../config/index.js';
import { startEmbeddedPostgres, stopEmbeddedPostgres } from './embedded.js';
import { initPool, closePool } from './pool.js';

/**
 * Makes sure a usable database connection exists before migrations/seeds run.
 * Boots the bundled local cluster when DATABASE_URL is absent.
 *
 * @returns {Promise<string>} the effective connection string
 */
export async function ensureDatabase() {
  if (config.useEmbeddedPg) {
    const url = await startEmbeddedPostgres();
    initPool(url);
    return url;
  }
  const url = resolveDatabaseUrl();
  initPool(url);
  return url;
}

/** Tears down only what this process owns (the embedded cluster). */
export async function releaseDatabase() {
  await closePool();
  if (config.useEmbeddedPg) await stopEmbeddedPostgres();
}

/**
 * Runs `fn` against a ready database and cleans up afterwards.
 * Used by the one-shot CLI entry points (migrate / seed / reset / dump-schema).
 */
export async function withDatabase(fn) {
  await ensureDatabase();
  try {
    return await fn(resolveDatabaseUrl());
  } finally {
    await releaseDatabase();
  }
}