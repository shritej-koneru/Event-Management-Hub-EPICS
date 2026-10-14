import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { getPool } from './pool.js';
import { ensureDatabase, releaseDatabase } from './bootstrap.js';

const MIGRATIONS_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'migrations');

export function migrationFiles() {
  return readdirSync(MIGRATIONS_DIR)
    .filter((file) => file.endsWith('.sql'))
    .sort();
}

/**
 * Applies every not-yet-applied migration, one transaction per file.
 * Safe to call on every boot — applied files are skipped.
 */
export async function migrate({ log = console.log } = {}) {
  const pool = getPool();

  await pool.query(`
    CREATE TABLE IF NOT EXISTS migrations (
      id         text PRIMARY KEY,
      applied_at timestamptz NOT NULL DEFAULT now()
    )
  `);

  const { rows } = await pool.query('SELECT id FROM migrations');
  const applied = new Set(rows.map((row) => row.id));
  const files = migrationFiles();

  let count = 0;
  for (const file of files) {
    if (applied.has(file)) continue;
    const sql = readFileSync(path.join(MIGRATIONS_DIR, file), 'utf8');
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query(sql);
      await client.query('INSERT INTO migrations (id) VALUES ($1)', [file]);
      await client.query('COMMIT');
      count += 1;
      log(`  applied ${file}`);
    } catch (err) {
      await client.query('ROLLBACK');
      throw new Error(`Migration ${file} failed: ${err.message}`, { cause: err });
    } finally {
      client.release();
    }
  }

  return { applied: count, total: files.length };
}

/** Drops every application object. Development convenience only. */
export async function dropAll() {
  const pool = getPool();
  await pool.query(`
    DROP TABLE IF EXISTS chat_messages,
                    announcements,
                    search_history,
                    bookmarks,
                    events,
                    users,
                    venues,
                    migrations
    CASCADE
  `);
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  await ensureDatabase();
  try {
    const { applied, total } = await migrate();
    console.log(`[migrate] ${applied} applied, ${total} total migration file(s)`);
  } catch (err) {
    console.error('[migrate]', err.message);
    process.exitCode = 1;
  } finally {
    await releaseDatabase();
  }
}