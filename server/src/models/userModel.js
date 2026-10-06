import { getPool } from '../db/pool.js';

/** Public projection — never leaks password_hash. */
export function toPublicUser(row, { bookmarks } = {}) {
  if (!row) return null;
  const user = {
    id: row.id,
    name: row.name,
    email: row.email,
    interests: Array.isArray(row.interests) ? row.interests : []
  };
  if (bookmarks !== undefined) user.bookmarks = bookmarks;
  return user;
}

export async function findUserByEmail(email) {
  const { rows } = await getPool().query('SELECT * FROM users WHERE email = $1', [email]);
  return rows[0] ?? null;
}

export async function findUserById(id) {
  const { rows } = await getPool().query('SELECT * FROM users WHERE id = $1', [id]);
  return rows[0] ?? null;
}

export async function insertUser({ id, name, email, passwordHash, interests = [] }) {
  const pool = getPool();
  const { rows } = await pool.query(
    `INSERT INTO users (id, name, email, password_hash, interests)
     VALUES ($1, $2, $3, $4, $5::jsonb)
     RETURNING *`,
    [id, name, email, passwordHash, JSON.stringify(interests)]
  );
  return rows[0];
}

export async function updateInterests(userId, interests) {
  const { rows } = await getPool().query(
    'UPDATE users SET interests = $2::jsonb WHERE id = $1 RETURNING *',
    [userId, JSON.stringify(interests)]
  );
  return rows[0] ?? null;
}

export async function countUsers() {
  const { rows } = await getPool().query('SELECT COUNT(*)::int AS count FROM users');
  return rows[0].count;
}