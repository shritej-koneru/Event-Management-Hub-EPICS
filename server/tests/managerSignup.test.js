import { afterEach, describe, expect, it } from 'vitest';
import { api } from './helpers/testContext.js';
import { config, resolveManagerSignup } from '../src/config/index.js';

// Website manager sign-up (POST /api/auth/register-manager) versus the app's student sign-up (POST /api/auth/register).
// The point: the role is decided by WHICH ENDPOINT is called, never by the request body, and the manager endpoint
// is refused unless the operator turned it on (it is off by default in production).

const unique = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
const account = (tag) => ({ name: `UITEST ${tag}`, email: `uitest-${tag}-${unique()}@example.test`, password: 'test-pass-123' });

const original = config.managerSignupEnabled;
afterEach(() => {
  config.managerSignupEnabled = original;
});

describe('resolveManagerSignup policy', () => {
  it('is off in production and on elsewhere when unset', () => {
    expect(resolveManagerSignup({}, true)).toBe(false);
    expect(resolveManagerSignup({}, false)).toBe(true);
  });

  it('honours an explicit setting in both directions', () => {
    for (const on of ['true', '1', 'yes', ' TRUE ']) expect(resolveManagerSignup({ ALLOW_MANAGER_SIGNUP: on }, true)).toBe(true);
    for (const off of ['false', '0', 'no']) expect(resolveManagerSignup({ ALLOW_MANAGER_SIGNUP: off }, false)).toBe(false);
  });

  it('treats an unrecognised value like unset', () => {
    expect(resolveManagerSignup({ ALLOW_MANAGER_SIGNUP: 'maybe' }, true)).toBe(false);
    expect(resolveManagerSignup({ ALLOW_MANAGER_SIGNUP: 'maybe' }, false)).toBe(true);
  });
});

describe('POST /api/auth/register-manager', () => {
  it('creates an organizer who can use the manager endpoints when enabled', async () => {
    config.managerSignupEnabled = true;
    const body = account('mgr');

    const res = await api().post('/api/auth/register-manager').send(body);

    expect(res.status).toBe(201);
    expect(res.body.user.role).toBe('organizer');
    expect(res.body.token).toBeTruthy();
    expect(JSON.stringify(res.body)).not.toContain(body.password);

    const manage = await api().get('/api/manage/overview').set('Authorization', `Bearer ${res.body.token}`);
    expect(manage.status).toBe(200);

    const login = await api().post('/api/auth/login').send({ email: body.email, password: body.password });
    expect(login.status).toBe(200);
    expect(login.body.user.role).toBe('organizer');
  });

  it('is refused with 403 MANAGER_SIGNUP_DISABLED and creates nothing when disabled', async () => {
    config.managerSignupEnabled = false;
    const body = account('off');

    const res = await api().post('/api/auth/register-manager').send(body);

    expect(res.status).toBe(403);
    expect(res.body.code).toBe('MANAGER_SIGNUP_DISABLED');
    const login = await api().post('/api/auth/login').send({ email: body.email, password: body.password });
    expect(login.status).toBe(401); // the account was not created
  });

  it('rejects a duplicate email with 409 and an invalid body with 400', async () => {
    config.managerSignupEnabled = true;
    const body = account('dup');
    expect((await api().post('/api/auth/register-manager').send(body)).status).toBe(201);

    expect((await api().post('/api/auth/register-manager').send(body)).status).toBe(409);
    expect((await api().post('/api/auth/register-manager').send({ ...body, email: 'not-an-email' })).status).toBe(400);
    expect((await api().post('/api/auth/register-manager').send({ ...account('short'), password: '123' })).status).toBe(400);
  });
});

describe('POST /api/auth/register (the app) stays student-only', () => {
  it('creates a student who is refused by the manager endpoints', async () => {
    config.managerSignupEnabled = true; // even with manager sign-up on
    const body = account('stu');

    const res = await api().post('/api/auth/register').send(body);

    expect(res.status).toBe(201);
    expect(res.body.user.role).toBe('student');
    const manage = await api().get('/api/manage/overview').set('Authorization', `Bearer ${res.body.token}`);
    expect(manage.status).toBe(403);
  });

  it('ignores a role field in the body, whether it asks for organizer or admin', async () => {
    for (const role of ['organizer', 'admin']) {
      const res = await api().post('/api/auth/register').send({ ...account(`role-${role}`), role });
      expect(res.status).toBe(201);
      expect(res.body.user.role).toBe('student');
    }
  });

  it('ignores a role field on the manager endpoint too: it can only ever make an organizer, never an admin', async () => {
    config.managerSignupEnabled = true;
    const res = await api().post('/api/auth/register-manager').send({ ...account('noadmin'), role: 'admin' });
    expect(res.status).toBe(201);
    expect(res.body.user.role).toBe('organizer');
  });
});

describe('GET /api/auth/manager-signup', () => {
  it('reports whether the website may offer manager sign-up, without authentication', async () => {
    config.managerSignupEnabled = true;
    expect((await api().get('/api/auth/manager-signup')).body).toEqual({ enabled: true });
    config.managerSignupEnabled = false;
    expect((await api().get('/api/auth/manager-signup')).body).toEqual({ enabled: false });
  });
});
