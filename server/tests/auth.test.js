import { beforeAll, describe, expect, it } from 'vitest';
import express from 'express';
import supertest from 'supertest';
import { api, as, DEMO, loginDemo, unique } from './helpers/testContext.js';
import { buildRateLimiter } from '../src/middleware/rateLimit.js';

describe('POST /api/auth/register', () => {
  it('creates a user and returns 201 with a token', async () => {
    const email = `${unique('user')}@uni.edu`;
    const response = await api()
      .post('/api/auth/register')
      .send({ name: 'Test Student', email, password: 'secret123', interests: ['tech'] });

    expect(response.status).toBe(201);
    expect(typeof response.body.token).toBe('string');
    expect(response.body.user).toMatchObject({ name: 'Test Student', email });
    expect(response.body.user.id).toMatch(/^usr_/);
    expect(response.body.user.interests).toEqual(['tech']);
  });

  it('never leaks the password hash', async () => {
    const email = `${unique('user')}@uni.edu`;
    const response = await api()
      .post('/api/auth/register')
      .send({ name: 'No Hash', email, password: 'secret123' });

    expect(response.body.user.password_hash).toBeUndefined();
    expect(response.body.user.passwordHash).toBeUndefined();
    expect(JSON.stringify(response.body)).not.toMatch(/\$2[aby]\$/);
  });

  it('rejects a duplicate email with 409 EMAIL_TAKEN', async () => {
    const email = `${unique('user')}@uni.edu`;
    const payload = { name: 'First', email, password: 'secret123' };

    const first = await api().post('/api/auth/register').send(payload);
    expect(first.status).toBe(201);

    const second = await api().post('/api/auth/register').send(payload);
    expect(second.status).toBe(409);
    expect(second.body.code).toBe('EMAIL_TAKEN');
  });

  it('rejects an invalid email with 400', async () => {
    const response = await api()
      .post('/api/auth/register')
      .send({ name: 'Bad', email: 'not-an-email', password: 'secret123' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a short password with 400', async () => {
    const response = await api()
      .post('/api/auth/register')
      .send({ name: 'Short', email: `${unique('u')}@uni.edu`, password: '123' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a missing name with 400', async () => {
    const response = await api()
      .post('/api/auth/register')
      .send({ email: `${unique('u')}@uni.edu`, password: 'secret123' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});

describe('POST /api/auth/login', () => {
  it('logs in the seeded demo user', async () => {
    const response = await api().post('/api/auth/login').send(DEMO);

    expect(response.status).toBe(200);
    expect(typeof response.body.token).toBe('string');
    expect(response.body.user).toMatchObject({ id: 'usr_001', email: DEMO.email });
    expect(response.body.user.interests).toContain('tech');
  });

  it('rejects a wrong password with 401 INVALID_CREDENTIALS', async () => {
    const response = await api()
      .post('/api/auth/login')
      .send({ email: DEMO.email, password: 'wrong-password' });

    expect(response.status).toBe(401);
    expect(response.body.code).toBe('INVALID_CREDENTIALS');
  });

  it('rejects an unknown email with the same 401 (no user enumeration)', async () => {
    const response = await api()
      .post('/api/auth/login')
      .send({ email: 'nobody@uni.edu', password: 'secret123' });

    expect(response.status).toBe(401);
    expect(response.body.code).toBe('INVALID_CREDENTIALS');
  });

  it('rejects missing credentials with 400', async () => {
    const response = await api().post('/api/auth/login').send({});
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});

describe('JWT-protected endpoints', () => {
  it('resolves the correct user from the token on GET /api/auth/me', async () => {
    const { token } = await loginDemo();
    const response = await as(token).get('/api/auth/me');

    expect(response.status).toBe(200);
    expect(response.body.user.id).toBe('usr_001');
    expect(response.body.user.email).toBe(DEMO.email);
    expect(response.body.user.bookmarks).toEqual(expect.arrayContaining(['evt_001', 'evt_005']));
  });

  it('identifies each distinct user correctly', async () => {
    const email = `${unique('multi')}@uni.edu`;
    const registered = await api()
      .post('/api/auth/register')
      .send({ name: 'Multi User', email, password: 'secret123' });

    const token = registered.body.token;
    const me = await as(token).get('/api/auth/me');

    expect(me.body.user.email).toBe(email);
    expect(me.body.user.id).toBe(registered.body.user.id);
    expect(me.body.user.id).not.toBe('usr_001');
  });

  it('returns 401 AUTH_REQUIRED without a token', async () => {
    const response = await api().get('/api/auth/me');
    expect(response.status).toBe(401);
    expect(response.body.code).toBe('AUTH_REQUIRED');
  });

  it('returns 401 INVALID_TOKEN for a tampered token', async () => {
    const { token } = await loginDemo();
    const response = await as(`${token}tampered`).get('/api/auth/me');

    expect(response.status).toBe(401);
    expect(response.body.code).toBe('INVALID_TOKEN');
  });

  it('updates interests for the authenticated user', async () => {
    const email = `${unique('int')}@uni.edu`;
    const { token } = (
      await api().post('/api/auth/register').send({ name: 'Interest User', email, password: 'secret123' })
    ).body;

    const updated = await as(token).put('/api/auth/me/interests').send({ interests: ['music', 'art'] });
    expect(updated.status).toBe(200);
    expect(updated.body.user.interests).toEqual(['music', 'art']);

    const me = await as(token).get('/api/auth/me');
    expect(me.body.user.interests).toEqual(['music', 'art']);
  });

  it('rejects a non-array interests payload with 400', async () => {
    const { token } = await loginDemo();
    const response = await as(token).put('/api/auth/me/interests').send({ interests: 'tech' });
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});
describe('POST /api/auth/me/bookmarks/:eventId', () => {
  let token;
  let eventId;

  beforeAll(async () => {
    ({ token } = await loginDemo());
    const created = await as(token).post('/api/events').send({
      title: `Bookmark Target ${unique('bm')}`,
      category: 'Tech',
      venue: 'Room 205',
      date: '2027-09-01',
      capacity: 10
    });
    eventId = created.body.event.id;
  });

  it('requires authentication', async () => {
    const response = await api().post(`/api/auth/me/bookmarks/${eventId}`);
    expect(response.status).toBe(401);
    expect(response.body.code).toBe('AUTH_REQUIRED');
  });

  it('adds a bookmark and returns 200', async () => {
    const response = await as(token).post(`/api/auth/me/bookmarks/${eventId}`);

    expect(response.status).toBe(200);
    expect(response.body.bookmarked).toBe(true);
    expect(response.body.eventId).toBe(eventId);
    expect(response.body.bookmarks).toContain(eventId);
  });

  it('is idempotent for an already-bookmarked event', async () => {
    const first = await as(token).post(`/api/auth/me/bookmarks/${eventId}`);
    const second = await as(token).post(`/api/auth/me/bookmarks/${eventId}`);

    expect(second.status).toBe(200);
    expect(second.body.bookmarks.filter((id) => id === eventId)).toHaveLength(1);
    expect(second.body.bookmarks.length).toBe(first.body.bookmarks.length);
  });

  it('returns 404 EVENT_NOT_FOUND for an unknown event', async () => {
    const response = await as(token).post('/api/auth/me/bookmarks/evt_does_not_exist');

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('EVENT_NOT_FOUND');
  });

  it('rejects a malformed event id with 400', async () => {
    const response = await as(token).post('/api/auth/me/bookmarks/bad%20id!');
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });
});

describe('DELETE /api/auth/me/bookmarks/:eventId', () => {
  let token;
  let eventId;

  beforeAll(async () => {
    ({ token } = await loginDemo());
    const created = await as(token).post('/api/events').send({
      title: `Unbookmark Target ${unique('ub')}`,
      category: 'Tech',
      venue: 'Room 205',
      date: '2027-09-02',
      capacity: 10
    });
    eventId = created.body.event.id;
    await as(token).post(`/api/auth/me/bookmarks/${eventId}`);
  });

  it('requires authentication', async () => {
    const response = await api().delete(`/api/auth/me/bookmarks/${eventId}`);
    expect(response.status).toBe(401);
  });

  it('removes a bookmark and returns 200', async () => {
    const response = await as(token).delete(`/api/auth/me/bookmarks/${eventId}`);

    expect(response.status).toBe(200);
    expect(response.body.bookmarked).toBe(false);
    expect(response.body.bookmarks).not.toContain(eventId);
  });

  it('is idempotent when the bookmark is already absent', async () => {
    const response = await as(token).delete(`/api/auth/me/bookmarks/${eventId}`);

    expect(response.status).toBe(200);
    expect(response.body.bookmarked).toBe(false);
  });

  it('returns 404 EVENT_NOT_FOUND for an unknown event', async () => {
    const response = await as(token).delete('/api/auth/me/bookmarks/evt_does_not_exist');

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('EVENT_NOT_FOUND');
  });
});

describe('GET /api/auth/me/bookmarks', () => {
  it('requires authentication', async () => {
    const response = await api().get('/api/auth/me/bookmarks');
    expect(response.status).toBe(401);
  });

  it('lists the user bookmarks', async () => {
    const { token } = await loginDemo();
    const response = await as(token).get('/api/auth/me/bookmarks');

    expect(response.status).toBe(200);
    expect(Array.isArray(response.body.bookmarks)).toBe(true);
    expect(response.body.bookmarks.length).toBeGreaterThan(0);
  });

  it('stays in sync with GET /api/auth/me', async () => {
    const { token } = await loginDemo();
    const created = await as(token).post('/api/events').send({
      title: `Sync Target ${unique('sy')}`,
      category: 'Tech',
      venue: 'Room 205',
      date: '2027-09-03',
      capacity: 10
    });

    await as(token).post(`/api/auth/me/bookmarks/${created.body.event.id}`);
    const me = await as(token).get('/api/auth/me');
    expect(me.body.user.bookmarks).toContain(created.body.event.id);

    await as(token).delete(`/api/auth/me/bookmarks/${created.body.event.id}`);
    const after = await as(token).get('/api/auth/me');
    expect(after.body.user.bookmarks).not.toContain(created.body.event.id);
  });

  it('does not leak another user bookmarks', async () => {
    const email = `${unique('iso')}@uni.edu`;
    const { token } = (
      await api()
        .post('/api/auth/register')
        .send({ name: 'Isolated', email, password: 'secret123' })
    ).body;

    const response = await as(token).get('/api/auth/me/bookmarks');
    expect(response.body.bookmarks).toEqual([]);
  });
});

describe('security hardening', () => {
  it('sets security headers on API responses', async () => {
    const response = await api().get('/ping');

    expect(response.headers['x-content-type-options']).toBe('nosniff');
    expect(response.headers['x-dns-prefetch-control']).toBeDefined();
    expect(response.headers['content-security-policy']).toBeDefined();
    expect(response.headers['x-powered-by']).toBeUndefined();
  });

  it('does not leak raw database error detail', async () => {
    const email = `${unique('leak')}@uni.edu`;
    const payload = { name: 'Leak', email, password: 'secret123' };

    await api().post('/api/auth/register').send(payload);
    const response = await api().post('/api/auth/register').send(payload);

    expect(response.status).toBe(409);
    expect(response.body.error).not.toMatch(/users_email_key|Key \(/i);
    expect(JSON.stringify(response.body)).not.toContain(email);
  });

it('attaches rate limit headers to the login endpoint', async () => {
    const response = await api()
      .post('/api/auth/login')
      .send({ email: 'nobody@uni.edu', password: 'wrong' });

    expect(response.status).toBe(401);
    // draft-7 emits a structured `RateLimit` header rather than `RateLimit-Limit`.
    expect(response.headers.ratelimit).toBeDefined();
  });

  it('returns 429 RATE_LIMITED once the configured budget is exhausted', async () => {
    const limiter = buildRateLimiter({
      windowMs: 60 * 1000,
      max: 3,
      message: 'Too many authentication attempts. Try again later.'
    });

    const app = express();
    app.post('/probe', limiter, (req, res) => res.status(401).json({ error: 'Invalid email or password', code: 'INVALID_CREDENTIALS' }));

    const statuses = [];
    for (let i = 0; i < 5; i += 1) {
      // Sequential so the limiter's counter is deterministic.
      // eslint-disable-next-line no-await-in-loop
      statuses.push((await supertest(app).post('/probe')).status);
    }

    expect(statuses.slice(0, 3)).toEqual([401, 401, 401]);
    expect(statuses.slice(3)).toEqual([429, 429]);
  });

  it('includes a Retry-After hint on the limited response', async () => {
    const limiter = buildRateLimiter({ windowMs: 60 * 1000, max: 1 });
    const app = express();
    // Non-2xx so the request counts against the budget.
    app.get('/probe', limiter, (req, res) =>
      res.status(401).json({ error: 'nope', code: 'INVALID_CREDENTIALS' })
    );

    await supertest(app).get('/probe');
    const limited = await supertest(app).get('/probe');

    expect(limited.status).toBe(429);
    expect(limited.body.code).toBe('RATE_LIMITED');
    expect(limited.headers['retry-after']).toBeDefined();
  });

  it('does not count successful requests against the budget', async () => {
    const limiter = buildRateLimiter({ windowMs: 60 * 1000, max: 2 });
    const app = express();
    app.get('/probe', limiter, (req, res) => res.json({ ok: true }));

    for (let i = 0; i < 5; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      const response = await supertest(app).get('/probe');
      expect(response.status).toBe(200);
    }
  });
});
