import { inject } from 'vitest';
import supertest from 'supertest';
import { initPool } from '../../src/db/pool.js';
import { createApp } from '../../src/app.js';

let context = null;

/**
 * Shared Express app bound to the migrated + seeded test database.
 * Built once per test process and reused by every suite.
 */
export function testContext() {
  if (!context) {
    const databaseUrl = inject('databaseUrl');
    if (!databaseUrl) {
      throw new Error('globalSetup did not provide "databaseUrl"');
    }
    initPool(databaseUrl);
    const app = createApp();
    context = { app, request: supertest(app) };
  }
  return context;
}

export function api() {
  return testContext().request;
}

/** Demo account created by the seed. */
export const DEMO = { email: 'alice@uni.edu', password: 'secret123' };

/** Logs in the demo user and returns `{ token, user }`. */
export async function loginDemo() {
  const response = await api().post('/api/auth/login').send(DEMO);
  if (response.status !== 200) {
    throw new Error(`demo login failed: ${response.status} ${JSON.stringify(response.body)}`);
  }
  return response.body;
}

/** Authenticated request helper. */
export function as(token) {
  const req = api();
  const auth = { Authorization: `Bearer ${token}` };
  return {
    get: (url) => req.get(url).set(auth),
    post: (url) => req.post(url).set(auth),
    put: (url) => req.put(url).set(auth),
    patch: (url) => req.patch(url).set(auth),
    delete: (url) => req.delete(url).set(auth)
  };
}

/** A unique value per test run so suites never collide in the shared database. */
export function unique(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}