import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',

    // Boots a real PostgreSQL cluster, creates emh_test, migrates and seeds it.
    globalSetup: './tests/globalSetup.js',

    // All suites share one database, so they run sequentially in a single
    // worker process. That also keeps module-level singletons (the pg pool,
    // the seeded app instance) alive across test files.
    pool: 'forks',
    maxWorkers: 1,
    minWorkers: 1,
    fileParallelism: false,

    include: ['tests/**/*.test.js'],
    testTimeout: 30_000,
    hookTimeout: 180_000,
    reporters: ['verbose']
  }
});