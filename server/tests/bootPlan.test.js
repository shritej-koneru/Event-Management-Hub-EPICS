import { describe, expect, it } from 'vitest';
import { query } from '../src/db/pool.js';
import { resolveBootPlan } from '../src/config/index.js';
import { migrationFiles, pendingMigrations } from '../src/db/migrate.js';
import { testContext } from './helpers/testContext.js';

describe('boot-time database policy (resolveBootPlan)', () => {
  it('keeps development/test behaviour: migrate and seed-if-empty', () => {
    expect(resolveBootPlan({}, false)).toEqual({ migrate: true, seedIfEmpty: true });
  });

  it('production does neither by default', () => {
    expect(resolveBootPlan({}, true)).toEqual({ migrate: false, seedIfEmpty: false });
    expect(resolveBootPlan({ NODE_ENV: 'production' }, true)).toEqual({ migrate: false, seedIfEmpty: false });
  });

  it('MIGRATE_ON_BOOT is an explicit opt-in in production', () => {
    for (const value of ['true', '1', 'YES']) {
      expect(resolveBootPlan({ MIGRATE_ON_BOOT: value }, true).migrate).toBe(true);
    }
    // opting in to migrations never turns on boot-time seeding
    expect(resolveBootPlan({ MIGRATE_ON_BOOT: 'true' }, true).seedIfEmpty).toBe(false);
  });

  it('MIGRATE_ON_BOOT=false is honoured in development', () => {
    for (const value of ['false', '0', 'no']) {
      expect(resolveBootPlan({ MIGRATE_ON_BOOT: value }, false).migrate).toBe(false);
    }
  });

  it('ignores unrecognised values and falls back to the environment default', () => {
    expect(resolveBootPlan({ MIGRATE_ON_BOOT: 'maybe' }, true).migrate).toBe(false);
    expect(resolveBootPlan({ MIGRATE_ON_BOOT: 'maybe' }, false).migrate).toBe(true);
  });
});

describe('pendingMigrations (read-only schema check used at production boot)', () => {
  it('is empty for the fully migrated test database', async () => {
    testContext(); // initialises the pool against the migrated test database
    expect(await pendingMigrations()).toEqual([]);
  });

  it('lists a migration that is recorded as not applied, without applying it', async () => {
    testContext();
    const last = migrationFiles().at(-1);
    const { rows } = await query('SELECT applied_at FROM migrations WHERE id = $1', [last]);
    expect(rows).toHaveLength(1);
    try {
      await query('DELETE FROM migrations WHERE id = $1', [last]);
      expect(await pendingMigrations()).toEqual([last]);
      // read-only: calling it must not have re-recorded the file
      const again = await query('SELECT 1 FROM migrations WHERE id = $1', [last]);
      expect(again.rows).toHaveLength(0);
    } finally {
      await query('INSERT INTO migrations (id, applied_at) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING', [
        last,
        rows[0].applied_at
      ]);
    }
    expect(await pendingMigrations()).toEqual([]);
  });

  it('treats a never-migrated database (no migrations table) as everything pending', async () => {
    const fakePool = { query: async () => ({ rows: [{ t: null }] }) };
    expect(await pendingMigrations(fakePool)).toEqual(migrationFiles());
  });

  it('returns migration files in apply order', () => {
    const files = migrationFiles();
    expect(files).toEqual([...files].sort());
    expect(files.length).toBeGreaterThanOrEqual(9);
  });
});
