/**
 * Vitest global setup.
 *
 * Boots the bundled local PostgreSQL cluster, points it at a dedicated
 * `emh_test` database, applies migrations and seeds it, then hands the
 * connection string to every test file via `inject('databaseUrl')`.
 *
 * Env vars are assigned BEFORE any src/ import so `src/config/index.js`
 * (which validates configuration at import time) sees test-safe values.
 */
process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = process.env.JWT_SECRET || 'test-only-secret';
process.env.USE_EMBEDDED_PG = 'true';
process.env.DATABASE_URL = '';
process.env.EMBEDDED_PG_DATABASE = process.env.EMBEDDED_PG_DATABASE || 'emh_test';

export default async function setup({ provide }) {
  // Static imports are hoisted, so every src/ module must be loaded dynamically
  // after the environment above is in place.
  const { ensureDatabase } = await import('../src/db/bootstrap.js');
  const { initPool } = await import('../src/db/pool.js');
  const { migrate, dropAll } = await import('../src/db/migrate.js');
  const { seed } = await import('../src/db/seeds/index.js');
  const { stopEmbeddedPostgres } = await import('../src/db/embedded.js');

  const databaseUrl = await ensureDatabase();
  initPool(databaseUrl);

  // A clean schema on every run, then the canonical seed data.
  await dropAll();
  await migrate({ log: () => {} });
  await seed({ log: () => {} });

  provide('databaseUrl', databaseUrl);

  return async () => {
    const { closePool } = await import('../src/db/pool.js');
    await closePool().catch(() => {});
    await stopEmbeddedPostgres();
  };
}