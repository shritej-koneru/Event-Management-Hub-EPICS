import { writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { ensureDatabase, releaseDatabase } from '../src/db/bootstrap.js';
import { getPool } from '../src/db/pool.js';
import { ROOT } from '../src/config/index.js';

/**
 * Dumps the live schema to src/db/schema/schema.sql.
 *   npm run db:schema
 *
 * The migrations folder stays authoritative; this file is a generated
 * reference snapshot of what the database actually looks like.
 */

const OUTPUT = path.join(ROOT, 'src', 'db', 'schema', 'schema.sql');

const TABLES = [
  'venues',
  'events',
  'users',
  'bookmarks',
  'search_history',
  'announcements',
  'venue_edges',
  'migrations'
];

await ensureDatabase();
try {
  const pool = getPool();
  const chunks = [
    '-- =============================================================================',
    '-- GENERATED FILE — do not edit by hand.',
    '-- Refresh with `npm run db:schema`.',
    '-- Authoritative definitions live in src/db/migrations/*.sql',
    '-- =============================================================================',
    ''
  ];

  for (const table of TABLES) {
    const { rows } = await pool.query(
      'SELECT column_name, data_type, is_nullable, column_default, character_maximum_length, udt_name, ordinal_position FROM information_schema.columns WHERE table_name = $1 ORDER BY ordinal_position',
      [table]
    );
    if (!rows.length) continue;

    const { rows: constraints } = await pool.query(
      `SELECT con.conname, con.contype, pg_get_constraintdef(con.oid) AS definition
       FROM pg_constraint con
       JOIN pg_class rel ON rel.oid = con.conrelid
       WHERE rel.relname = $1
       ORDER BY con.conname`,
      [table]
    );
    const { rows: indexes } = await pool.query('SELECT indexname FROM pg_indexes WHERE tablename = $1 ORDER BY indexname', [
      table
    ]);

    chunks.push(`-- ---- ${table} ${'-'.repeat(Math.max(0, 60 - table.length))}`);
    chunks.push(
      rows
        .map((row) => {
          const type = row.data_type === 'USER-DEFINED' ? row.udt_name : row.data_type;
          const length = row.character_maximum_length ? `(${row.character_maximum_length})` : '';
          const nullable = row.is_nullable === 'YES' ? '' : ' NOT NULL';
          const def = row.column_default ? ` DEFAULT ${row.column_default}` : '';
          return `  ${row.column_name.padEnd(16)}${(type + length).padEnd(18)}${nullable}${def}`;
        })
        .join('\n')
    );

    const pk = constraints.filter((c) => c.contype === 'p');
    const uq = constraints.filter((c) => c.contype === 'u');
    const fk = constraints.filter((c) => c.contype === 'f');
    for (const group of [pk, uq, fk]) {
      for (const constraint of group) chunks.push(`  -- ${constraint.definition}`);
    }
    for (const index of indexes) chunks.push(`  -- INDEX ${index.indexname}`);
    chunks.push('');
  }

  mkdirSync(path.dirname(OUTPUT), { recursive: true });
  writeFileSync(OUTPUT, chunks.join('\n'), 'utf8');
  console.log(`[db:schema] wrote ${OUTPUT}`);
} catch (err) {
  console.error('[db:schema] failed:', err.message);
  process.exitCode = 1;
} finally {
  await releaseDatabase();
}