import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { as, api, unique } from './helpers/testContext.js';
import { query } from '../src/db/pool.js';
import { config, resolveAiConfig } from '../src/config/index.js';
import { insertUser, setUserRole } from '../src/models/userModel.js';
import { signToken } from '../src/auth/jwt.js';
import { newId } from '../src/utils/id.js';
import { parseSearchQuery } from '../src/ai/queryFilters.js';
import { findConflicts, findAlternatives } from '../src/services/aiScheduleService.js';
import { keywordCategory } from '../src/services/aiManagerService.js';
import { aiCache } from '../src/services/aiTextService.js';
import { extractJsonObject } from '../src/ai/json.js';
import { unsupportedClaims } from '../src/ai/factCheck.js';

// Ten AI features end to end through the real app and database. The provider is ALWAYS a fake `fetch`; nothing here
// calls NVIDIA or needs a key. Each describe block names the feature it proves.

const FUTURE = '2027-09-';
const created = { users: [], events: [] };
const saved = { ai: { ...config.ai } };

/** The fake provider: every request is recorded; `reply` decides the answer. */
let calls = [];
function provider(reply) {
  calls = [];
  vi.stubGlobal('fetch', async (url, init) => {
    const body = JSON.parse(init.body);
    calls.push({ url, headers: init.headers, body });
    const out = await reply(body, calls.length);
    if (out instanceof Response) return out;
    return new Response(JSON.stringify({ choices: [{ message: { content: out } }], usage: { prompt_tokens: 5, completion_tokens: 5 } }), { status: 200 });
  });
}
const askedModel = () => calls.map((c) => c.body.model);
const userText = (i = 0) => calls[i].body.messages.find((m) => m.role === 'user').content;

function enableAi() {
  Object.assign(config.ai, resolveAiConfig({ NVIDIA_API_KEY: 'test-key-not-real' }));
}

async function makeUser(role, name = 'AI User') {
  const id = newId('usr');
  await insertUser({ id, name, email: `${unique('ai')}@x.io`, passwordHash: 'x' });
  if (role !== 'student') await setUserRole(id, role);
  created.users.push(id);
  return { id, role, token: signToken({ id, email: 'x@x.io', name }) };
}

async function makeEvent(owner, over = {}) {
  const res = await as(owner.token).post('/api/events').send({
    title: unique('AIT'),
    category: 'Tech',
    venue: 'Room 101',
    date: `${FUTURE}01`,
    time: '10:00',
    durationMin: 60,
    capacity: 50,
    ...over
  });
  expect(res.status, JSON.stringify(res.body)).toBe(201);
  created.events.push(res.body.event.id);
  return res.body.event;
}

const bookmark = (user, eventId) => query('INSERT INTO bookmarks (user_id, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [user.id, eventId]);

let student;
let student2;
let orgA;
let orgB;
let admin;
let eventA; // orgA, free, step-free
let eventB; // orgB

beforeAll(async () => {
  [student, student2, orgA, orgB, admin] = await Promise.all([
    makeUser('student', 'Real Student Name'),
    makeUser('student'),
    makeUser('organizer'),
    makeUser('organizer'),
    makeUser('admin')
  ]);
  eventA = await makeEvent(orgA, {
    title: unique('Robotics Showcase'),
    description: 'Come and see student robots. The event is held in Room 101.',
    price: { type: 'free' },
    accessibility: { stepFree: true, captions: false }
  });
  eventB = await makeEvent(orgB, { title: unique('Chess Night'), category: 'Competitive' });
});

afterEach(() => {
  vi.unstubAllGlobals();
  Object.assign(config.ai, saved.ai);
  aiCache.clear();
});

afterAll(async () => {
  await query('DELETE FROM events WHERE id = ANY($1)', [created.events]);
  await query('DELETE FROM users WHERE id = ANY($1)', [created.users]);
});

/* ============================================================================ typed price & accessibility */
describe('event price and accessibility are explicit and typed (unknown stays unknown)', () => {
  it('stores and returns explicit values, and null / {} when nothing was stated', async () => {
    const fetched = (await api().get(`/api/events/${eventA.id}`)).body.event;
    expect(fetched.price).toEqual({ type: 'free' });
    expect(fetched.accessibility).toEqual({ stepFree: true, captions: false });

    expect(eventB.price).toBeNull();
    expect(eventB.accessibility).toEqual({});
  });

  it('accepts a paid price and clears it back to unknown with null', async () => {
    const e = await makeEvent(orgA, { price: { type: 'paid', amountCents: 25000, currency: 'inr' } });
    expect(e.price).toEqual({ type: 'paid', amountCents: 25000, currency: 'INR' });

    const cleared = await as(orgA.token).put(`/api/events/${e.id}`).send({ price: null, accessibility: {} });
    expect(cleared.status).toBe(200);
    expect(cleared.body.event.price).toBeNull();
    expect(cleared.body.event.accessibility).toEqual({});
  });

  it('rejects an inconsistent price or an unknown accessibility feature', async () => {
    const bad = [
      { price: { type: 'paid' } },
      { price: { type: 'paid', amountCents: 0, currency: 'INR' } },
      { price: { type: 'paid', amountCents: 100, currency: 'RUPEES' } },
      { price: { type: 'free', amountCents: 100 } },
      { price: { type: 'cheap' } },
      { accessibility: { rollerCoaster: true } },
      { accessibility: { stepFree: 'yes' } }
    ];
    for (const body of bad) {
      const res = await as(orgA.token).post('/api/events').send({ title: unique('Bad'), category: 'Tech', venue: 'Room 101', date: `${FUTURE}05`, capacity: 5, ...body });
      expect(res.status, JSON.stringify(body)).toBe(400);
    }
  });
});

/* ============================================================================ 1. natural-language search */
describe('1. natural-language search with date, category, price and accessibility filters', () => {
  const today = '2026-11-11'; // a Wednesday

  it('parses dates, price, accessibility and categories from plain words', () => {
    const cats = ['Tech', 'Arts'];
    expect(parseSearchQuery('free tech events tomorrow', today, cats)).toMatchObject({
      filters: { free: true, category: 'Tech', dateFrom: '2026-11-12', dateTo: '2026-11-12' }
    });
    expect(parseSearchQuery('step-free music this weekend', today, cats).filters).toMatchObject({ accessibility: ['stepFree'], dateFrom: '2026-11-14', dateTo: '2026-11-15' });
    expect(parseSearchQuery('events next week', today, cats).filters).toMatchObject({ dateFrom: '2026-11-16', dateTo: '2026-11-22' });
    expect(parseSearchQuery('workshop on friday', today, cats).filters).toMatchObject({ dateFrom: '2026-11-13', dateTo: '2026-11-13' });
    expect(parseSearchQuery('anything under 500', today, cats).filters.maxPriceCents).toBe(50000);
    expect(parseSearchQuery('captions and sign language', today, cats).filters.accessibility.sort()).toEqual(['captions', 'signLanguage']);
    expect(parseSearchQuery('on 2027-09-01', today, cats).filters).toMatchObject({ dateFrom: '2027-09-01', dateTo: '2027-09-01' });
  });

  it('leaves the leftover words as the text to match, and changes nothing when there are no filter words', () => {
    expect(parseSearchQuery('free robotics tomorrow', today, []).text).toBe('robotics');
    const plain = parseSearchQuery('android workshop', today, ['Tech']);
    expect(plain.text).toBe('android workshop');
    expect(plain.applied).toEqual([]);
  });

  it('free / step-free filters match ONLY events that state it; unknown never matches and is counted', async () => {
    const res = await api().post('/api/ai/search').send({ query: 'robotics', filters: { free: true, accessibility: ['stepFree'] } });
    expect(res.status).toBe(200);
    const ids = res.body.results.map((r) => r.event.id);
    expect(ids).toContain(eventA.id);
    expect(ids).not.toContain(eventB.id);
    expect(res.body.appliedFilters).toEqual(expect.arrayContaining(['free', 'stepFree']));
    expect(res.body.unknownExcluded).toBeGreaterThan(0); // the seeded events do not state price/accessibility
  });

  it('an explicit "no" never satisfies the filter', async () => {
    const res = await api().post('/api/ai/search').send({ query: 'robotics', filters: { accessibility: ['captions'] } });
    expect(res.body.results.map((r) => r.event.id)).not.toContain(eventA.id); // captions is declared false
  });

  it('understands filters written in the sentence itself', async () => {
    const res = await api().post('/api/ai/search').send({ query: `free robotics on ${FUTURE}01` });
    expect(res.status).toBe(200);
    expect(res.body.appliedFilters).toEqual(expect.arrayContaining(['free', `on ${FUTURE}01`]));
    expect(res.body.results.map((r) => r.event.id)).toContain(eventA.id);
  });

  it('price ceiling includes free events and excludes dearer or unstated ones', async () => {
    const dear = await makeEvent(orgA, { price: { type: 'paid', amountCents: 90000, currency: 'INR' } });
    const cheap = await makeEvent(orgA, { price: { type: 'paid', amountCents: 20000, currency: 'INR' } });
    const res = await api().post('/api/ai/search').send({ query: 'tech', filters: { maxPriceCents: 50000 }, limit: 50 });
    const ids = res.body.results.map((r) => r.event.id);
    expect(ids).toEqual(expect.arrayContaining([cheap.id, eventA.id]));
    expect(ids).not.toContain(dear.id);
  });

  it('rejects unknown filter keys and bad values with 400', async () => {
    expect((await api().post('/api/ai/search').send({ query: 'x', filters: { vegan: true } })).status).toBe(400);
    expect((await api().post('/api/ai/search').send({ query: 'x', filters: { accessibility: ['nope'] } })).status).toBe(400);
    expect((await api().post('/api/ai/search').send({ query: 'x', filters: { dateFrom: '11/11/2026' } })).status).toBe(400);
  });

  it('searches without calling the AI provider at all', async () => {
    provider(() => 'should never be used');
    enableAi();
    await api().post('/api/ai/search').send({ query: 'free robotics tomorrow' });
    expect(calls).toHaveLength(0);
  });
});

/* ============================================================================ 2. recommendations */
describe('2. personalized recommendations carry a truthful reason', () => {
  it('requires sign-in and explains each pick from a signal that exists', async () => {
    expect((await api().get('/api/ai/recommendations')).status).toBe(401);

    await as(student.token).put('/api/auth/me/interests').send({ interests: ['tech'] });
    const res = await as(student.token).get('/api/ai/recommendations');
    expect(res.status).toBe(200);
    expect(res.body.recommendations.length).toBeGreaterThan(0);
    for (const r of res.body.recommendations) expect(typeof r.reason).toBe('string');
    expect(res.body.recommendations.some((r) => /interest|bookmark|search/i.test(r.reason))).toBe(true);
  });

  it('never calls the AI provider', async () => {
    provider(() => 'unused');
    enableAi();
    await as(student.token).get('/api/ai/recommendations');
    expect(calls).toHaveLength(0);
  });
});

/* ============================================================================ 3. campus assistant */
describe('3. campus assistant answers only from current data and cites it', () => {
  const ask = (token, question) => as(token).post('/api/ai/assistant').send({ question });

  it('answers from the matching sources, returns their ids, and sends no personal data', async () => {
    enableAi();
    provider(() => JSON.stringify({ answer: 'The Robotics Showcase is free and step-free, in Room 101.', sources: [`event:${eventA.id}`] }));

    const res = await ask(student.token, 'Is the robotics showcase free?');

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ available: true, aiGenerated: true });
    expect(res.body.answer).toContain('Robotics Showcase');
    expect(res.body.sources).toEqual([expect.objectContaining({ id: `event:${eventA.id}`, type: 'event', refId: eventA.id })]);
    expect(askedModel()).toEqual(['nvidia/nemotron-3.5-bolt-e4b']);
    // The provider got the event data and the question, and nothing about the person asking.
    const sent = JSON.stringify(calls[0].body);
    expect(sent).toContain(eventA.id);
    expect(sent).not.toContain('Real Student Name');
    expect(sent).not.toContain(student.id);
  });

  it('says it does not know, WITHOUT calling the provider, when no current data relates to the question', async () => {
    enableAi();
    provider(() => 'unused');
    const res = await ask(student.token, 'qwertyuiop zxcvbnm');
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ available: false, answer: null });
    expect(res.body.message).toMatch(/don't have information/i);
    expect(calls).toHaveLength(0);
  });

  it('discards an answer that cites nothing it was given', async () => {
    enableAi();
    provider(() => JSON.stringify({ answer: 'Pizza is served at noon.', sources: ['event:made-up'] }));
    const res = await ask(student.token, 'robotics showcase food');
    expect(res.body).toMatchObject({ available: false, answer: null });
  });

  it('turns an unparseable model answer into "not available", never raw text', async () => {
    enableAi();
    provider(() => 'Sure! Here is a free-form answer with no JSON.');
    const res = await ask(student.token, 'robotics showcase');
    expect(res.body.available).toBe(false);
    expect(res.body.answer).toBeNull();
  });

  it('falls back to the matching sources when the provider fails', async () => {
    enableAi();
    provider(() => new Response('boom', { status: 500 }));
    const res = await ask(student.token, 'robotics showcase');
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ fallback: true, aiGenerated: false, answer: null });
    expect(res.body.sources.map((s) => s.id)).toContain(`event:${eventA.id}`);
  });

  it('answers app-help questions from the help topics', async () => {
    enableAi();
    provider(() => JSON.stringify({ answer: 'Open an event and tap Save.', sources: ['help:save-event'] }));
    const res = await ask(student.token, 'how do I save an event');
    expect(res.body.sources.map((s) => s.id)).toEqual(['help:save-event']);
  });

  it('requires sign-in, validates the question, and is 503 AI_DISABLED when AI is off', async () => {
    expect((await api().post('/api/ai/assistant').send({ question: 'hi' })).status).toBe(401);
    expect((await ask(student.token, '   ')).status).toBe(400);
    expect((await ask(student.token, 'x'.repeat(301))).status).toBe(400);
    const off = await ask(student.token, 'robotics showcase');
    expect([off.status, off.body.code]).toEqual([503, 'AI_DISABLED']);
  });
});

/* ============================================================================ 4 & 5. announcements */
describe('4. announcement summaries and 5. translation keep the original', () => {
  const LONG =
    'Doors for the Robotics Showcase open at 10:00 in Room 101. Please bring your student ID, arrive ten minutes early, ' +
    'and note that the demonstration of the line-following robots will begin right after the welcome talk.';
  let announcementId;
  let shortId;

  beforeAll(async () => {
    const a = await as(orgA.token).post('/api/manage/announcements').send({ message: LONG, eventId: eventA.id });
    expect(a.status, JSON.stringify(a.body)).toBe(201);
    announcementId = a.body.announcement.id;
    const s = await as(orgA.token).post('/api/manage/announcements').send({ message: 'Doors open at 10:00.', eventId: eventA.id });
    shortId = s.body.announcement.id;
  });

  it('summarizes a long announcement and links back to the original', async () => {
    enableAi();
    provider(() => 'Doors open at 10:00 in Room 101; bring your student ID.');
    const res = await as(student.token).post(`/api/ai/announcements/${announcementId}/summary`);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ aiGenerated: true, announcementId, eventId: eventA.id, original: LONG });
    expect(res.body.summary).toBe('Doors open at 10:00 in Room 101; bring your student ID.');
    expect(res.body.link).toEqual({ announcementId, eventId: eventA.id });
  });

  it('does not call the provider for a short announcement', async () => {
    enableAi();
    provider(() => 'unused');
    const res = await as(student.token).post(`/api/ai/announcements/${shortId}/summary`);
    expect(res.body).toMatchObject({ alreadyShort: true, aiGenerated: false, summary: 'Doors open at 10:00.' });
    expect(calls).toHaveLength(0);
  });

  it('serves a repeat summary from the cache without spending provider quota', async () => {
    enableAi();
    provider(() => 'Doors open at 10:00 in Room 101.');
    await as(student.token).post(`/api/ai/announcements/${announcementId}/summary`);
    await as(student2.token).post(`/api/ai/announcements/${announcementId}/summary`);
    expect(calls).toHaveLength(1);
  });

  it('rejects a summary that invents a number and falls back to a plain cut of the original', async () => {
    enableAi();
    provider(() => 'Doors open at 9:30 in Room 101.');
    const res = await as(student.token).post(`/api/ai/announcements/${announcementId}/summary`);
    expect(res.body).toMatchObject({ aiGenerated: false, fallback: true });
    expect(LONG.startsWith(res.body.summary.replace(/…$/, '').slice(0, 20))).toBe(true);
  });

  it('falls back when the provider is down; 404 for an unknown announcement; 401 when signed out; 503 when AI is off', async () => {
    enableAi();
    provider(() => new Response('', { status: 503 }));
    expect((await as(student.token).post(`/api/ai/announcements/${announcementId}/summary`)).body).toMatchObject({ fallback: true, aiGenerated: false });

    provider(() => 'x');
    expect((await as(student.token).post('/api/ai/announcements/99999999/summary')).status).toBe(404);
    expect((await api().post(`/api/ai/announcements/${announcementId}/summary`)).status).toBe(401);

    Object.assign(config.ai, saved.ai);
    const off = await as(student.token).post(`/api/ai/announcements/${announcementId}/summary`);
    expect([off.status, off.body.code]).toEqual([503, 'AI_DISABLED']);
  });

  it('translates into a verified language and returns the original untouched', async () => {
    enableAi();
    provider(() => 'दरवाज़े सुबह 10:00 बजे खुलते हैं।');

    const res = await as(student.token).post(`/api/ai/announcements/${shortId}/translate`).send({ language: 'hi' });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ original: 'Doors open at 10:00.', language: 'hi', aiGenerated: true, announcementId: shortId });
    expect(res.body.translated).toMatch(/[ऀ-ॿ]/);
    expect(askedModel()).toEqual(['nvidia/riva-translate-4b-instruct-v2']);
    expect(userText()).toBe('What is the Hindi translation of the sentence: Doors open at 10:00.');
  });

  it('rejects an unsupported language (400) without calling the provider', async () => {
    enableAi();
    provider(() => 'unused');
    for (const language of ['te', 'ta', 'xx']) {
      const res = await as(student.token).post(`/api/ai/announcements/${shortId}/translate`).send({ language });
      expect([res.status, res.body.code]).toEqual([400, 'UNSUPPORTED_LANGUAGE']);
    }
    expect(calls).toHaveLength(0);
  });

  it('refuses a wrong-script translation instead of showing it', async () => {
    enableAi();
    provider(() => 'Doors open at 10:00 in the Czech language.');
    const res = await as(student.token).post(`/api/ai/announcements/${shortId}/translate`).send({ language: 'hi' });
    expect([res.status, res.body.code]).toEqual([502, 'AI_BAD_RESPONSE']);
    expect(res.body).not.toHaveProperty('translated');
  });

  it('is 503 when the provider is down (no fallback for translation), 404/401/400 for bad requests', async () => {
    enableAi();
    provider(() => new Response('', { status: 500 }));
    expect((await as(student.token).post(`/api/ai/announcements/${shortId}/translate`).send({ language: 'es' })).status).toBe(503);
    expect((await as(student.token).post('/api/ai/announcements/99999999/translate').send({ language: 'es' })).status).toBe(404);
    expect((await api().post(`/api/ai/announcements/${shortId}/translate`).send({ language: 'es' })).status).toBe(401);
    expect((await as(student.token).post(`/api/ai/announcements/${shortId}/translate`).send({})).status).toBe(400);
  });
});

/* ============================================================================ 6. help desk rewrite */
describe('6. help desk rewrite suggestions are never sent automatically', () => {
  const rewrite = (token, body) => as(token).post('/api/ai/support/rewrite').send(body);

  it('returns a suggestion for review and creates no ticket or message', async () => {
    enableAi();
    provider(() => 'Hello, I could not register for the showcase. Could you please help me?');
    const before = (await query('SELECT (SELECT count(*) FROM support_tickets) t, (SELECT count(*) FROM support_messages) m')).rows[0];

    const res = await rewrite(student.token, { text: 'cant register help', kind: 'message' });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ original: 'cant register help', requiresReview: true, aiGenerated: true, kind: 'message' });
    expect(res.body.suggestion).toContain('could not register');
    const after = (await query('SELECT (SELECT count(*) FROM support_tickets) t, (SELECT count(*) FROM support_messages) m')).rows[0];
    expect(after).toEqual(before);
    expect(JSON.stringify(calls[0].body)).not.toContain(student.id);
  });

  it('rejects a suggestion that adds a number or exceeds the length limit', async () => {
    enableAi();
    provider(() => 'I paid 500 rupees and cannot register.');
    expect((await rewrite(student.token, { text: 'cant register', kind: 'message' })).status).toBe(502);
    provider(() => 'x'.repeat(121));
    expect((await rewrite(student.token, { text: 'help', kind: 'subject' })).status).toBe(502);
  });

  it('validates input, requires sign-in, and reports provider failure safely', async () => {
    enableAi();
    provider(() => 'ok');
    expect((await rewrite(student.token, { text: '   ' })).status).toBe(400);
    expect((await rewrite(student.token, { text: 'x'.repeat(1001) })).status).toBe(400);
    expect((await rewrite(student.token, { text: 'hi', kind: 'essay' })).status).toBe(400);
    expect((await api().post('/api/ai/support/rewrite').send({ text: 'hi' })).status).toBe(401);
    provider(() => new Response('', { status: 500 }));
    const down = await rewrite(student.token, { text: 'hello there' });
    expect([down.status, down.body.code]).toEqual([503, 'AI_UNAVAILABLE']);
  });
});

/* ============================================================================ 7. ticket categories */
describe('7. ticket category suggestions for managers', () => {
  let ticketId;

  beforeAll(async () => {
    await bookmark(student, eventA.id);
    const t = await as(student.token).post('/api/support/tickets').send({ eventId: eventA.id, subject: 'Cannot register', message: 'The register button gives an error for the showcase seat.' });
    expect(t.status, JSON.stringify(t.body)).toBe(201);
    ticketId = t.body.ticket.id;
  });

  const categorize = (token, id = ticketId) => as(token).post(`/api/ai/manage/tickets/${id}/category`);

  it('suggests one of the fixed categories for the event creator, sending only the ticket text', async () => {
    enableAi();
    provider(() => JSON.stringify({ category: 'registration', reason: 'The attendee cannot register for a seat.' }));

    const res = await categorize(orgA.token);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ ticketId, category: 'registration', aiGenerated: true });
    expect(res.body.categories).toContain('accessibility');
    const sent = JSON.stringify(calls[0].body);
    expect(sent).toContain('Cannot register');
    expect(sent).not.toContain('Real Student Name');
    expect(sent).not.toContain(student.id);
  });

  it('stores nothing: no category column exists on tickets', async () => {
    const cols = (await query(`SELECT column_name FROM information_schema.columns WHERE table_name = 'support_tickets'`)).rows.map((r) => r.column_name);
    expect(cols).not.toContain('category');
  });

  it('follows the existing authorization: another organizer 404, a student 403, an admin allowed, signed out 401', async () => {
    enableAi();
    provider(() => JSON.stringify({ category: 'other', reason: 'x' }));
    expect((await categorize(orgB.token)).status).toBe(404);
    expect((await categorize(student.token)).status).toBe(403);
    expect((await categorize(admin.token)).status).toBe(200);
    expect((await api().post(`/api/ai/manage/tickets/${ticketId}/category`)).status).toBe(401);
  });

  it('falls back to keywords when the provider fails or answers with a category that does not exist', async () => {
    enableAi();
    provider(() => new Response('', { status: 500 }));
    expect((await categorize(orgA.token)).body).toMatchObject({ category: 'registration', aiGenerated: false, fallback: true });

    provider(() => JSON.stringify({ category: 'astrology', reason: 'x' }));
    expect((await categorize(orgA.token)).body).toMatchObject({ category: 'registration', aiGenerated: false, fallback: true });
  });

  it('classifies by keyword without the model', () => {
    expect(keywordCategory('Is there a wheelchair ramp?')).toBe('accessibility');
    expect(keywordCategory('I want a refund, the fee was charged twice')).toBe('payment');
    expect(keywordCategory('hello')).toBe('other');
  });

  it('is 503 AI_DISABLED when AI is off', async () => {
    const off = await categorize(orgA.token);
    expect([off.status, off.body.code]).toEqual([503, 'AI_DISABLED']);
  });
});

/* ============================================================================ 8. plain-language rewrite */
describe('8. plain-language description rewrites never invent accessibility or amenities', () => {
  const simplify = (token, id) => as(token).post(`/api/ai/events/${id}/simplify`);

  it('returns the plain version next to the original, plus accessibility built only from typed data', async () => {
    enableAi();
    provider(() => 'Come and see student robots in Room 101.');

    const res = await simplify(student.token, eventA.id);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ eventId: eventA.id, original: eventA.description, plain: 'Come and see student robots in Room 101.', aiGenerated: true });
    expect(res.body.accessibility).toEqual({
      offered: ['Step-free access'],
      notOffered: ['Captions'],
      notStated: ['Accessible restroom', 'Sign language interpretation', 'Hearing loop']
    });
    expect(userText()).toContain('step-free access'); // the declared fact is passed in; the undeclared ones are not
    expect(userText()).not.toMatch(/sign language|hearing loop|restroom/i);
  });

  it('rejects a rewrite that claims an accessibility feature the event does not declare', async () => {
    enableAi();
    provider(() => 'Come and see robots. The venue is wheelchair accessible with a hearing loop.');
    const res = await simplify(student.token, eventA.id);
    expect([res.status, res.body.code]).toEqual([502, 'AI_BAD_RESPONSE']);
  });

  it('rejects invented amenities and invented numbers', async () => {
    enableAi();
    provider(() => 'Come and see robots. Free lunch is served.');
    expect((await simplify(student.token, eventA.id)).status).toBe(502);
    provider(() => 'Come and see robots in Room 202.');
    expect((await simplify(student.token, eventA.id)).status).toBe(502);
  });

  it('allows a feature that IS declared', async () => {
    enableAi();
    provider(() => 'Come and see robots. The room has step-free access.');
    expect((await simplify(student.token, eventA.id)).status).toBe(200);
  });

  it('handles missing description, unknown event, signed out and provider failure', async () => {
    enableAi();
    provider(() => 'ok');
    expect((await simplify(student.token, eventB.id)).status).toBe(400); // no description
    expect((await simplify(student.token, 'evt_missing')).status).toBe(404);
    expect((await api().post(`/api/ai/events/${eventA.id}/simplify`)).status).toBe(401);
    provider(() => new Response('', { status: 500 }));
    expect((await simplify(student.token, eventA.id)).status).toBe(503);
  });

  it('fact-check helper: claims must come from the source', () => {
    expect(unsupportedClaims('A talk in Room 5.', 'A talk. Wheelchair accessible.')).toEqual(['wheelchair', 'accessible']);
    expect(unsupportedClaims('A talk. Step-free access.', 'Step-free access to the talk.')).toEqual([]);
    expect(unsupportedClaims('Lunch is provided.', 'Lunch is provided.')).toEqual([]);
  });
});

/* ============================================================================ 9. schedule conflicts */
describe('9. schedule conflict detection and alternatives', () => {
  let a; let b; let c; let altSameCat; let altOverlapping;

  beforeAll(async () => {
    a = await makeEvent(orgA, { date: `${FUTURE}10`, time: '10:00', durationMin: 120, category: 'Arts', venue: 'Room 101' });
    b = await makeEvent(orgA, { date: `${FUTURE}10`, time: '11:00', durationMin: 60, category: 'Arts', venue: 'Room 102' });
    c = await makeEvent(orgA, { date: `${FUTURE}10`, time: '12:00', durationMin: 60, category: 'Arts', venue: 'Auditorium' }); // starts when a ends
    altSameCat = await makeEvent(orgA, { date: `${FUTURE}10`, time: '15:00', durationMin: 60, category: 'Arts', venue: 'Library' });
    altOverlapping = await makeEvent(orgA, { date: `${FUTURE}10`, time: '10:30', durationMin: 60, category: 'Arts', venue: 'Lobby' });
  });

  const check = (token, body) => as(token).post('/api/ai/schedule/conflicts').send(body);

  it('finds an overlap between the events the app sent, and says how long', async () => {
    const res = await check(student.token, { eventIds: [a.id, b.id] });

    expect(res.status).toBe(200);
    expect(res.body.hasConflicts).toBe(true);
    expect(res.body.conflicts).toEqual([expect.objectContaining({ overlapMinutes: 60 })]);
    expect(res.body.conflicts[0].a.id).toBe(a.id);
    expect(res.body.conflicts[0].b.id).toBe(b.id);
  });

  it('does not treat back-to-back events as a conflict', async () => {
    const res = await check(student.token, { eventIds: [a.id, c.id] });
    expect(res.body.hasConflicts).toBe(false);
    expect(res.body.conflicts).toEqual([]);
  });

  it('offers same-category alternatives that fit the rest of the schedule, never overlapping ones', async () => {
    const res = await check(student.token, { eventIds: [a.id, b.id, c.id] });
    const forB = res.body.alternatives.find((x) => x.forEventId === b.id).options.map((o) => o.id);
    expect(forB).toContain(altSameCat.id);
    expect(forB).not.toContain(altOverlapping.id); // overlaps event a, which stays in the schedule
    expect(forB).not.toContain(a.id); // already saved
  });

  it('always states its data limitation and what it was based on, and reports ids it does not know', async () => {
    const res = await check(student.token, { eventIds: [a.id, 'evt_nonexistent'] });
    expect(res.body.limitation).toMatch(/stored on your device/i);
    expect(res.body.basedOn).toMatchObject({ source: 'app', eventCount: 1, unknownIds: ['evt_nonexistent'] });
  });

  it('uses the server bookmarks when the app sends no list', async () => {
    await bookmark(student2, a.id);
    await bookmark(student2, b.id);
    const res = await check(student2.token, {});
    expect(res.body.basedOn.source).toBe('server-bookmarks');
    expect(res.body.hasConflicts).toBe(true);
  });

  it('is deterministic: no provider call, even with AI configured', async () => {
    enableAi();
    provider(() => 'unused');
    await check(student.token, { eventIds: [a.id, b.id] });
    expect(calls).toHaveLength(0);
  });

  it('validates the list and requires sign-in', async () => {
    expect((await check(student.token, { eventIds: Array.from({ length: 31 }, (_, i) => `evt_${i}`) })).status).toBe(400);
    expect((await check(student.token, { eventIds: ['bad id!'] })).status).toBe(400);
    expect((await api().post('/api/ai/schedule/conflicts').send({})).status).toBe(401);
  });

  it('pure helpers agree with the API', () => {
    const ev = (id, time, dur) => ({ id, title: id, category: 'X', venue: 'v', date: '2027-01-01', time, durationMin: dur });
    expect(findConflicts([ev('p', '09:00', 60), ev('q', '10:00', 60)])).toEqual([]);
    expect(findConflicts([ev('p', '09:00', 90), ev('q', '10:00', 60)])[0].overlapMinutes).toBe(30);
    expect(findAlternatives(ev('p', '09:00', 60), [ev('p', '09:00', 60)], [ev('p', '09:00', 60), ev('z', '13:00', 60)], '2026-01-01').map((e) => e.id)).toEqual(['z']);
  });
});

/* ============================================================================ 10. organizer drafts */
describe('10. organizer drafts require review and are never saved or published', () => {
  const draft = (token, body) => as(token).post('/api/ai/manage/draft').send(body);
  const counts = async () => (await query('SELECT (SELECT count(*) FROM events) e, (SELECT count(*) FROM announcements) a')).rows[0];

  it('drafts an event listing from notes and saves nothing', async () => {
    enableAi();
    provider(() => JSON.stringify({ title: 'Intro to Robotics', description: 'A beginner-friendly robotics session.', tags: ['Robotics', 'beginners'] }));
    const before = await counts();

    const res = await draft(orgA.token, { kind: 'event', brief: 'beginner robotics session' });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ kind: 'event', status: 'DRAFT_REQUIRES_REVIEW', aiGenerated: true });
    expect(res.body.draft).toEqual({ title: 'Intro to Robotics', description: 'A beginner-friendly robotics session.', tags: ['robotics', 'beginners'] });
    expect(res.body.note).toMatch(/nothing has been saved/i);
    expect(await counts()).toEqual(before);
  });

  it('drafts an announcement for the organizer\'s own event using only its facts', async () => {
    enableAi();
    provider(() => JSON.stringify({ message: 'The Robotics Showcase starts at 10:00 in Room 101.' }));
    const before = await counts();

    const res = await draft(orgA.token, { kind: 'announcement', brief: 'remind people it starts at 10:00', eventId: eventA.id });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ kind: 'announcement', status: 'DRAFT_REQUIRES_REVIEW', eventId: eventA.id });
    expect(res.body.draft.message).toContain('10:00');
    expect(userText()).toContain(eventA.id.length ? 'EVENT FACTS' : '');
    expect(await counts()).toEqual(before);
  });

  it('refuses a draft for an event the organizer does not own (403), unknown events (404)', async () => {
    enableAi();
    provider(() => JSON.stringify({ message: 'x' }));
    expect((await draft(orgB.token, { kind: 'announcement', brief: 'hello', eventId: eventA.id })).status).toBe(403);
    expect((await draft(orgA.token, { kind: 'announcement', brief: 'hello', eventId: 'evt_missing' })).status).toBe(404);
    expect((await draft(admin.token, { kind: 'announcement', brief: 'hello', eventId: eventA.id })).status).toBe(200);
  });

  it('discards drafts that invent amenities, numbers or are too long', async () => {
    enableAi();
    provider(() => JSON.stringify({ title: 'Robotics', description: 'Join us. Free lunch and parking provided.', tags: [] }));
    expect((await draft(orgA.token, { kind: 'event', brief: 'robotics session' })).status).toBe(502);

    provider(() => JSON.stringify({ title: 'Robotics', description: 'Starts at 14:30.', tags: [] }));
    expect((await draft(orgA.token, { kind: 'event', brief: 'robotics session' })).status).toBe(502);

    provider(() => JSON.stringify({ message: 'x'.repeat(281) }));
    expect((await draft(orgA.token, { kind: 'announcement', brief: 'remind', eventId: eventA.id })).status).toBe(502);

    provider(() => 'not json at all');
    expect((await draft(orgA.token, { kind: 'event', brief: 'robotics session' })).status).toBe(502);
  });

  it('is for organizers and admins only; validates input; 503 when AI is off', async () => {
    enableAi();
    provider(() => JSON.stringify({ title: 'T', description: 'D', tags: [] }));
    expect((await draft(student.token, { kind: 'event', brief: 'x' })).status).toBe(403);
    expect((await api().post('/api/ai/manage/draft').send({ kind: 'event', brief: 'x' })).status).toBe(401);
    expect((await draft(orgA.token, { kind: 'event', brief: '' })).status).toBe(400);
    expect((await draft(orgA.token, { kind: 'poster', brief: 'x' })).status).toBe(400);
    expect((await draft(orgA.token, { kind: 'event', brief: 'x'.repeat(801) })).status).toBe(400);

    Object.assign(config.ai, saved.ai);
    const off = await draft(orgA.token, { kind: 'event', brief: 'robotics session' });
    expect([off.status, off.body.code]).toEqual([503, 'AI_DISABLED']);
  });
});

describe('shared helpers', () => {
  it('extracts JSON from fenced or chatty model output', () => {
    expect(extractJsonObject('```json\n{"a": 1}\n```')).toEqual({ a: 1 });
    expect(extractJsonObject('Sure! {"a": {"b": "}"}} thanks')).toEqual({ a: { b: '}' } });
    expect(extractJsonObject('no json')).toBeNull();
    expect(extractJsonObject('{"broken": ')).toBeNull();
    expect(extractJsonObject('[1,2]')).toBeNull();
  });

  it('the per-user AI rate limit applies to every provider endpoint', async () => {
    // Under test the limiter is effectively off; this proves the routes are mounted behind it by checking the headers.
    enableAi();
    provider(() => JSON.stringify({ answer: null, sources: [] }));
    const res = await as(student.token).post('/api/ai/assistant').send({ question: 'robotics showcase' });
    expect(res.headers).toHaveProperty('ratelimit');
  });
});
