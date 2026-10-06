import { existsSync, readFileSync, writeFileSync, copyFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * First-run helper: `npm run setup`.
 *
 * Creates .env from .env.example and generates a random JWT_SECRET if the
 * placeholder is still empty. Existing .env files are never overwritten
 * without an explicit --force flag.
 *
 * Deliberately does NOT import src/config/index.js — that module validates
 * configuration at import time, which is exactly what this script exists to fix.
 */

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const envPath = path.join(ROOT, '.env');
const examplePath = path.join(ROOT, '.env.example');
const force = process.argv.includes('--force');

if (existsSync(envPath) && !force) {
  console.log('.env already exists — nothing to do.');
  console.log('Re-run with `npm run setup -- --force` to recreate it.');
  process.exit(0);
}

if (!existsSync(examplePath)) {
  console.error('.env.example is missing — cannot create .env');
  process.exit(1);
}

copyFileSync(examplePath, envPath);

const secret = randomBytes(48).toString('hex');
const contents = readFileSync(envPath, 'utf8').replace(/^JWT_SECRET=.*$/m, `JWT_SECRET=${secret}`);

writeFileSync(envPath, contents);

console.log('.env created.');
console.log('  JWT_SECRET was generated randomly — keep this file out of version control.');
console.log('');
console.log('Next:');
console.log('  npm run db:migrate   # create tables');
console.log('  npm run db:seed      # load venues + events + demo user');
console.log('  npm run dev          # start on http://localhost:3000');
console.log('');
console.log('Or just run `npm start`, which migrates and seeds automatically.');