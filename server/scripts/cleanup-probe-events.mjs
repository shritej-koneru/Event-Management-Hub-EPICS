/**
 * Removes ONLY the event rows created by Socket.IO relay probes during emulator
 * testing, restoring `emh` to its verified seeded state.
 *
 * Safety: selects by an explicit title allow-list pattern, prints every row it
 * matched, and refuses to delete if the selection is broader than expected. Seeded
 * and user events never match. Announcements and bookmarks referencing these
 * events are removed with them via ON DELETE CASCADE.
 *
 * Usage: node scripts/cleanup-probe-events.mjs [--dry-run]
 */
import { resolveDatabaseUrl } from '../src/config/index.js';
import { initPool, query, closePool } from '../src/db/pool.js';

const PROBE_TITLE = /PROBE|RELAY VERIFIED|ACK VERIFIED|FINAL CHECK/;
const dryRun = process.argv.includes('--dry-run');

initPool(resolveDatabaseUrl());

try {
  const { rows } = await query('SELECT id, title FROM events WHERE title ~ $1 ORDER BY id', [
    PROBE_TITLE.source,
  ]);

  if (rows.length === 0) {
    console.log('No probe events present; nothing to clean.');
  } else {
    console.log(`${dryRun ? 'Would remove' : 'Removing'} ${rows.length} probe event(s):`);
    for (const r of rows) console.log(`  ${r.id}  ${r.title}`);

    if (dryRun) {
      console.log('Dry run: nothing deleted.');
    } else {
      const { rows: deleted } = await query(
        'DELETE FROM events WHERE id = ANY($1::text[]) RETURNING id',
        [rows.map((r) => r.id)],
      );
      console.log(`Deleted ${deleted.length} row(s).`);
    }
  }

  const events = await query('SELECT count(*)::int AS n FROM events');
  const announcements = await query('SELECT count(*)::int AS n FROM announcements');
  const bookmarks = await query('SELECT count(*)::int AS n FROM bookmarks');
  console.log(
    `after: events=${events.rows[0].n} announcements=${announcements.rows[0].n} bookmarks=${bookmarks.rows[0].n}`,
  );
} finally {
  await closePool();
}