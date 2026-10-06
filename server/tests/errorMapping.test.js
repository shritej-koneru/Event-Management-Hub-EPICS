import { describe, expect, it } from 'vitest';
import { api, as, loginDemo, unique } from './helpers/testContext.js';
import { toApiError, ApiError } from '../src/utils/errors.js';

/**
 * Regression coverage for body-parser / library error mapping.
 *
 * Malformed JSON and oversized payloads are client faults. Before toApiError()
 * learned about them they surfaced as 500 INTERNAL, which misreports the fault
 * and pollutes server error monitoring. These tests pin the 4xx mapping.
 */
describe('malformed request bodies', () => {
  it('returns 400 INVALID_REQUEST for malformed JSON instead of 500', async () => {
    const response = await api()
      .post('/api/auth/login')
      .set('Content-Type', 'application/json')
      // Valid JSON opener, never closed -> body-parser raises entity.parse.failed
      .send('{"email": "alice@uni.edu", "password":');

    expect(response.status).toBe(400);
    expect(response.status).not.toBe(500);
    expect(response.body.code).toBe('INVALID_REQUEST');
    expect(typeof response.body.error).toBe('string');
    expect(response.body.error.length).toBeGreaterThan(0);
  });

  it('never leaks a stack trace or driver detail in the malformed-JSON body', async () => {
    const response = await api()
      .post('/api/auth/login')
      .set('Content-Type', 'application/json')
      .send('{"email":');

    const serialized = JSON.stringify(response.body);
    expect(serialized).not.toMatch(/at\s+\w+\s+\(/); // stack frames
    expect(serialized).not.toMatch(/node_modules/); // internal paths
    expect(serialized).not.toMatch(/SELECT|INSERT|pg_/i); // driver internals
  });

  it('rejects malformed JSON on every JSON-body route the same way', async () => {
    const { token } = await loginDemo();

    const targets = [
      { method: 'post', url: '/api/auth/register' },
      { method: 'post', url: '/api/auth/login' },
      { method: 'post', url: '/api/ai/search' },
      { method: 'post', url: '/api/navigation/path' },
      { method: 'post', url: '/api/events', auth: token },
      { method: 'put', url: '/api/auth/me/interests', auth: token }
    ];

    for (const target of targets) {
      const req = target.auth ? as(target.auth)[target.method](target.url) : api()[target.method](target.url);
      const response = await req
        .set('Content-Type', 'application/json')
        .send('{"broken":');

      expect(response.status, `${target.method.toUpperCase()} ${target.url}`).toBe(400);
      expect(response.body.code, `${target.method.toUpperCase()} ${target.url}`).toBe('INVALID_REQUEST');
    }
  });

  it('distinguishes malformed JSON from schema validation failure', async () => {
    // Well-formed JSON that fails the schema is VALIDATION_ERROR, not INVALID_REQUEST.
    const response = await api()
      .post('/api/auth/login')
      .send({ email: 'not-an-email', password: '' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a JSON array where an object is required', async () => {
    // `[1,2,3]` is syntactically valid JSON, so body-parser accepts it and the
    // zod schema is what rejects it -> VALIDATION_ERROR, still a 400.
    const response = await api()
      .post('/api/auth/login')
      .set('Content-Type', 'application/json')
      .send('[1,2,3]');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a bare JSON string body with 400', async () => {
    const response = await api()
      .post('/api/auth/login')
      .set('Content-Type', 'application/json')
      .send('"not-an-object"');

    expect(response.status).toBe(400);
    expect([ 'INVALID_REQUEST', 'VALIDATION_ERROR' ]).toContain(response.body.code);
  });
});

describe('toApiError unit mapping', () => {
  it('maps entity.parse.failed to 400 INVALID_REQUEST', () => {
    const err = toApiError({ type: 'entity.parse.failed' });
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.code).toBe('INVALID_REQUEST');
  });

  it('maps entity.too.large to 400 PAYLOAD_TOO_LARGE', () => {
    const err = toApiError({ type: 'entity.too.large' });
    expect(err.status).toBe(400);
    expect(err.code).toBe('PAYLOAD_TOO_LARGE');
  });

  it('maps PostgreSQL unique_violation to 409 DUPLICATE', () => {
    const err = toApiError({ code: '23505' });
    expect(err.status).toBe(409);
    expect(err.code).toBe('DUPLICATE');
  });

  it('keeps a declared 4xx status as 400 rather than promoting it to 500', () => {
    const err = toApiError({ status: 422, message: 'unprocessable' });
    expect(err.status).toBe(400);
    expect(err.code).toBe('INVALID_REQUEST');
  });

  it('passes an existing ApiError through untouched', () => {
    const original = ApiError.conflict('already exists', 'DUPLICATE');
    expect(toApiError(original)).toBe(original);
  });

  it('falls back to 500 INTERNAL for an unrecognised failure', () => {
    const err = toApiError(new Error('boom'));
    expect(err.status).toBe(500);
    expect(err.code).toBe('INTERNAL');
  });

  it('does not forward the underlying message for an unknown 500', () => {
    const err = toApiError(new Error('password abc123 for user bob'));
    expect(err.message).toBe('Internal server error');
    expect(err.message).not.toContain('abc123');
  });
});