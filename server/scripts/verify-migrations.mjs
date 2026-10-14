/**
 * Migration-rebuild verification against a SCRATCH database.
 *
 *   npm run verify:migrations
 *
 * Answers the question the test suite cannot: can a brand-new database be built
 * from the migration files alone, and are the migrations re-runnable? The vitest
 * suite exercises behaviour against an already-built schema, so a broken
 * cold-start path would not be caught there.
 *
 * The script refuses to run against `emh`, `emh_test` or any `postgres*`
 * maintenance database, so a destructive verification can never hit a
 * development or shared database.
 *
 * Environment:
 *   SCRATCH_DATABASE  database to build in (default emh_migration_check)
 */

import { config } from '../src/config/index.js';

const ALLOWED = /^[a-z][a-z0-9_]*$/;
const scratch = process.env.SCRATCH_DATABASE || 'emh_migration_check';
const PROTECTED = ['emh', 'emh_test', 'postgres', 'template0', 'template1'];

if (!ALLOWED.test(scratch) || PROTECTED.includes(scratch)) {
  console.error(
    `[migrations] refusing unsafe SCRATCH_DATABASE name: ${scratch}\n` +
    `[migrations] must match ${ALLOWED} and not be one of: ${PROTECTED.join(', ')}`
  );
  process.exit(2);
}

const { startEmbeddedPostgres, stopEmbeddedPostgres } = await import('../src/db/embedded.js');
const { initPool, query, closePool, dropDatabaseIfExists, createDatabaseIfNotExists } =
  await import('../src/db/pool.js');
const { migrate, dropAll } = await import('../src/db/migrate.js');
const { seed } = await import('../src/db/seeds/index.js');

const { port, user, password } = config.embeddedPg;
const creds = `postgres://${encodeURIComponent(user)}:${encodeURIComponent(password)}@127.0.0.1:${port}`;
const adminUrl = `${creds}/postgres`;
const scratchUrl = `${creds}/${scratch}`;

let failures = 0;
const check = (label, ok, detail = '') => {
  if (!ok) failures += 1;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
};

if (!config.useEmbeddedPg) {
  console.error('[migrations] needs the bundled PostgreSQL (unset DATABASE_URL) — refusing to touch a shared server');
  process.exit(2);
}

console.log(`[migrations] scratch database: ${scratch}\n`);

await startEmbeddedPostgres();

// Start from a database that definitely does not exist yet.
await dropDatabaseIfExists(scratch, adminUrl).catch((err) => {
  console.error('[migrations] could not pre-clean scratch database:', err.message);
  process.exit(1);
});
const created = await createDatabaseIfNotExists(scratch, adminUrl);
console.log(`[migrations] created empty database "${scratch}": ${created}\n`);

// ---------------------------------------------------------------------------
console.log('1. cold build from an empty database');
initPool(scratchUrl);
let migrationLog = [];
let buildError = null;
try {
  await migrate({ log: (line) => migrationLog.push(line) });
  await seed({ log: () => {} });
} catch (err) {
  buildError = err;
}
check('cold build succeeds', buildError === null, buildError ? buildError.message : '');

const applied = migrationLog.filter((l) => /applied/.test(l));
check('all four migrations applied', applied.length === 4, `${applied.length}: ${applied.join(' | ')}`);
for (const file of [
  '001_init.sql',
  '002_integrity_and_retention.sql',
  '003_announcements_event_id.sql',
  '004_chat_messages.sql'
]) {
  check(`${file} ran`, migrationLog.some((l) => l.includes(file)));
}

const actualTables = (await query(
  `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name`
)).rows.map((r) => r.table_name);

for (const table of [
  'events',
  'venues',
  'users',
  'bookmarks',
  'announcements',
  'chat_messages',
  'search_history',
  'migrations'
]) {
  check(`table ${table} exists`, actualTables.includes(table), actualTables.join(','));
}

// The `migrations` ledger must record every file so a re-run skips cleanly.
const recorded = (await query(`SELECT count(*)::int AS n FROM migrations`)).rows[0].n;
check('migrations ledger records every file', Number(recorded) === 4, `${recorded} rows`);

const counts = (await query(
  `SELECT (SELECT count(*) FROM events)::int       AS events,
          (SELECT count(*) FROM venues)::int       AS venues,
          (SELECT count(*) FROM users)::int        AS users,
          (SELECT count(*) FROM announcements)::int AS announcements`
)).rows[0];
check('events seeded', counts.events >= 12, `${counts.events}`);
check('venues seeded', counts.venues === 14, `${counts.venues}`);
check('demo user seeded', counts.users >= 1, `${counts.users}`);
check('announcements seeded', counts.announcements >= 1, `${counts.announcements}`);

// Naming contract: no orphan venues.
const orphans = (await query(
  `SELECT e.id, e.venue_id FROM events e LEFT JOIN venues v ON v.id = e.venue_id WHERE v.id IS NULL`
)).rows;
check('no event references a missing venue', orphans.length === 0,
  orphans.length ? JSON.stringify(orphans) : '');

// The FK on events.venue_id must actually be enforced by the database.
const fk = (await query(
  `SELECT tc.constraint_name
     FROM information_schema.table_constraints tc
     JOIN information_schema.key_column_usage k
       ON k.constraint_name = tc.constraint_name
    WHERE tc.table_name = 'events' AND tc.constraint_type = 'FOREIGN KEY'`
)).rows.map((r) => r.constraint_name);
check('events.venue_id has a foreign key', fk.length > 0, fk.join(',') || 'none');

let fkEnforced = true;
try {
  await query(
    `INSERT INTO events (id, title, category, venue_id, date, time, capacity)
     VALUES ('__fk_probe__', 'Probe', 'Tech', 'no-such-venue', '2026-01-01', '10:00', 10)`
  );
  fkEnforced = false;
  await query(`DELETE FROM events WHERE id = '__fk_probe__'`);
} catch {
  // expected: the insert is refused
}
check('foreign key rejects an unknown venue_id', fkEnforced);

// Required schema fields are all NOT NULL. `venue` is a display name joined in
// from venues, so the stored column is venue_id.
const nulls = (await query(
  `SELECT count(*)::int AS n FROM events
    WHERE id IS NULL OR title IS NULL OR category IS NULL
       OR venue_id IS NULL OR date IS NULL OR capacity IS NULL`
)).rows[0].n;
check('no event missing a required field', nulls === 0, `${nulls} rows`);

const notNull = (await query(
  `SELECT column_name FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'events' AND is_nullable = 'NO'`
)).rows.map((r) => r.column_name);
for (const column of ['id', 'title', 'category', 'venue_id', 'date', 'capacity']) {
  check(`events.${column} is NOT NULL`, notNull.includes(column), notNull.join(','));
}

// The agreed schema must not reintroduce split date/time columns.
const dateCols = (await query(
  `SELECT column_name FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'events'`
)).rows.map((r) => r.column_name.toLowerCase());
const leaked = ['startdate', 'starttime', 'enddate', 'endtime'].filter((c) => dateCols.includes(c));
check('events has no startDate/startTime/endDate/endTime columns', leaked.length === 0,
  leaked.length ? leaked.join(',') : dateCols.join(','));

// ---------------------------------------------------------------------------
console.log('\n2. re-running migrations over an already-built schema');
const before = (await query(`SELECT count(*)::int AS n FROM events`)).rows[0].n;
let rerunError = null;
try {
  await migrate({ log: () => {} });
} catch (err) {
  rerunError = err;
}
check('re-run does not throw', rerunError === null, rerunError ? rerunError.message : '');
const after = (await query(`SELECT count(*)::int AS n FROM events`)).rows[0].n;
check('re-run preserves seeded data', before === after, `${before} -> ${after}`);

// ---------------------------------------------------------------------------
console.log('\n3. drop -> migrate -> seed is repeatable');
let cycleError = null;
try {
  await dropAll();
  await migrate({ log: () => {} });
  await seed({ log: () => {} });
} catch (err) {
  cycleError = err;
}
check('full reset cycle succeeds', cycleError === null, cycleError ? cycleError.message : '');
const rebuilt = (await query(`SELECT count(*)::int AS n FROM events`)).rows[0].n;
check('rebuilt database is populated', rebuilt >= 12, `${rebuilt}`);
const rebuiltAnn = (await query(`SELECT count(*)::int AS n FROM announcements`)).rows[0].n;
check('rebuilt announcements present', rebuiltAnn >= 1, `${rebuiltAnn}`);
const orphanAfter = (await query(
  `SELECT count(*)::int AS n FROM events e LEFT JOIN venues v ON v.id = e.venue_id WHERE v.id IS NULL`
)).rows[0].n;
check('no orphans after rebuild', Number(orphanAfter) === 0, `${orphanAfter}`);

// `registered` must stay within capacity at the database level.
let capEnforced = true;
try {
  await query(
    `INSERT INTO events (id, title, category, venue_id, date, time, capacity, registered)
     VALUES ('__cap_probe__', 'Probe', 'Tech',
             (SELECT id FROM venues LIMIT 1), '2026-01-01', '10:00', 5, 99)`
  );
  capEnforced = false;
  await query(`DELETE FROM events WHERE id = '__cap_probe__'`);
} catch {
  // expected: registered > capacity is refused
}
check('CHECK constraint rejects registered > capacity', capEnforced);

// ---------------------------------------------------------------------------
console.log(`\n[migrations] ${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}`);

// Release the pool before dropping, or the drop would block on our own session.
await closePool().catch(() => {});

if (failures === 0) {
  await dropDatabaseIfExists(scratch, adminUrl);
  console.log(`[migrations] dropped scratch database "${scratch}".`);
} else {
  console.log(`[migrations] scratch database "${scratch}" was left in place for inspection.`);
}

// Leave the cluster as we found it: a reused cluster was not ours to stop.
await stopEmbeddedPostgres().catch(() => {});
process.exitCode = failures === 0 ? 0 : 1;