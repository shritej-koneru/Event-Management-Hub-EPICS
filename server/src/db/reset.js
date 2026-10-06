import { ensureDatabase, releaseDatabase } from './bootstrap.js';
import { dropAll, migrate } from './migrate.js';
import { seed } from './seeds/index.js';

/**
 * Development helper: drop every table, re-run migrations, re-seed.
 *   npm run db:reset
 */
await ensureDatabase();
try {
  console.log('[reset] dropping all tables');
  await dropAll();
  await migrate();
  await seed();
  console.log('[reset] done');
} catch (err) {
  console.error('[reset] failed:', err.message);
  process.exitCode = 1;
} finally {
  await releaseDatabase();
}