import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import supertest from 'supertest';
import { createApp, FRONTEND_DIST } from '../src/app.js';

// Same-origin hosting of the built web frontend. Uses a throwaway dist/ fixture and never needs seeded data:
// the point is routing (what answers `/`, a browser route, /api, /ping and /socket.io), not the API's content.
const INDEX = '<!doctype html><html><body><div id="root">EMH-FIXTURE</div><script type="module" src="/assets/app-abc123.js"></script></body></html>';

let dist;
let web;
let apiOnly;

beforeAll(() => {
  dist = fs.mkdtempSync(path.join(os.tmpdir(), 'emh-dist-'));
  fs.mkdirSync(path.join(dist, 'assets'));
  fs.writeFileSync(path.join(dist, 'index.html'), INDEX);
  fs.writeFileSync(path.join(dist, 'assets', 'app-abc123.js'), 'console.log("fixture")');
  fs.writeFileSync(path.join(dist, 'favicon.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>');
  web = supertest(createApp({ frontendDir: dist }));
  apiOnly = supertest(createApp({ frontendDir: path.join(dist, 'does-not-exist') }));
});

afterAll(() => fs.rmSync(dist, { recursive: true, force: true }));

describe('frontend hosting', () => {
  it('serves index.html at / without caching it', async () => {
    const res = await web.get('/');
    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toMatch(/text\/html/);
    expect(res.text).toContain('EMH-FIXTURE');
    expect(res.headers['cache-control']).toBe('no-cache');
  });

  it('serves index.html for client-side routes (deep links and refresh)', async () => {
    for (const route of ['/events', '/events/evt_001', '/map/campus']) {
      const res = await web.get(route).set('Accept', 'text/html');
      expect(res.status, route).toBe(200);
      expect(res.text, route).toContain('EMH-FIXTURE');
    }
  });

  it('serves hashed assets as immutable and other files uncached', async () => {
    const js = await web.get('/assets/app-abc123.js');
    expect(js.status).toBe(200);
    expect(js.headers['cache-control']).toContain('immutable');
    const icon = await web.get('/favicon.svg');
    expect(icon.status).toBe(200);
    expect(icon.headers['cache-control']).toBe('no-cache');
  });

  it('returns a real 404 for a missing asset instead of the HTML page', async () => {
    const res = await web.get('/assets/missing-deadbeef.js');
    expect(res.status).toBe(404);
    expect(res.text).not.toContain('EMH-FIXTURE');
  });

  it('keeps unknown /api paths as the API JSON 404, not the frontend page', async () => {
    const res = await web.get('/api/definitely-not-a-route').set('Accept', 'text/html');
    expect(res.status).toBe(404);
    expect(res.headers['content-type']).toMatch(/json/);
    expect(res.body.code).toBe('NOT_FOUND');
    expect(res.text).not.toContain('EMH-FIXTURE');
  });

  it('keeps /ping a JSON health endpoint, never the frontend page', async () => {
    // 200 with a database, 500 without one (this test app has none); either way it is the handler's JSON.
    const res = await web.get('/ping').set('Accept', 'text/html');
    expect(res.headers['content-type']).toMatch(/json/);
    expect(res.text).not.toContain('EMH-FIXTURE');
  });

  it('never answers /socket.io with the frontend page', async () => {
    for (const p of ['/socket.io/', '/socket.io/?EIO=4&transport=polling']) {
      const res = await web.get(p).set('Accept', 'text/html');
      expect(res.text, p).not.toContain('EMH-FIXTURE');
      expect(res.status, p).toBe(404);
    }
  });

  it('does not answer non-GET requests on browser routes with the page', async () => {
    const res = await web.post('/events').send({});
    expect(res.status).toBe(404);
  });

  it('allows the OpenStreetMap tile host as an image source and nothing broader', async () => {
    const csp = (await web.get('/')).headers['content-security-policy'];
    expect(csp).toContain("img-src 'self' data: https://*.tile.openstreetmap.org");
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("default-src 'self'");
  });
});

describe('without a built frontend (Android-only, dev)', () => {
  it('behaves exactly like the API-only server: JSON 404 at /', async () => {
    const res = await apiOnly.get('/');
    expect(res.status).toBe(404);
    expect(res.headers['content-type']).toMatch(/json/);
    expect(res.body.code).toBe('NOT_FOUND');
  });
});

describe('default frontend location', () => {
  it('resolves to frontend/dist next to server/', () => {
    expect(FRONTEND_DIST).toBe(path.resolve(import.meta.dirname, '..', '..', 'frontend', 'dist'));
  });
});
