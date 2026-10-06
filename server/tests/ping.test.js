import { describe, expect, it } from 'vitest';
import { api } from './helpers/testContext.js';
import { config, resolveDatabaseName } from '../src/config/index.js';

describe('GET /ping', () => {
  it('returns a successful health payload', async () => {
    const response = await api().get('/ping');

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      status: 'ok',
      ok: true,
      service: 'emh-server',
      database: 'connected'
    });
    expect(response.body.contractVersion).toBeTruthy();
  });

  it('publishes the canonical venue catalogue', async () => {
    const response = await api().get('/ping');
    const venues = response.body.venues;

    expect(Array.isArray(venues)).toBe(true);
    expect(venues).toHaveLength(14);
    expect(venues.map((v) => v.id)).toContain('room-204');
    expect(venues.find((v) => v.id === 'room-204').name).toBe('Room 204');
  });

  it('requires no authentication', async () => {
    const response = await api().get('/ping').set('Authorization', '');
    expect(response.status).toBe(200);
  });
});

describe('GET /ping database identity', () => {
  // The live verifier scripts refuse to run unless /ping names a scratch
  // database, so this field is a safety guard, not decoration.
  it('names the database the instance serves outside production', async () => {
    if (config.isProd) {
      // Production must not disclose it; nothing to assert beyond the absence.
      const response = await api().get('/ping');
      expect(response.body.databaseName).toBeUndefined();
      return;
    }

    const response = await api().get('/ping');

    expect(response.body.databaseName).toBe(resolveDatabaseName());
    expect(response.body.databaseName).toBe(config.embeddedPg.database);
  });

  it('never leaks connection credentials in the name', async () => {
    const response = await api().get('/ping');

    // A bare database name only: no user, password, port or query string.
    expect(response.body.databaseName).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(JSON.stringify(response.body)).not.toMatch(/\$2[aby]\$|password/);
  });

  it('derives the name from the connection string, credentials stripped', () => {
    expect(resolveDatabaseName()).toBe(config.embeddedPg.database);
  });
});

describe('unknown routes', () => {
  it('returns a structured 404', async () => {
    const response = await api().get('/api/does-not-exist');

    expect(response.status).toBe(404);
    expect(response.body.code).toBe('NOT_FOUND');
    expect(typeof response.body.error).toBe('string');
  });
});