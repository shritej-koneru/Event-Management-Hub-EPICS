import { describe, expect, it } from 'vitest';
import { api, as, loginDemo, unique } from './helpers/testContext.js';

describe('POST /api/ai/search', () => {
  it('returns ranked results with scores and reasons', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'android workshop' });

    expect(response.status).toBe(200);
    expect(response.body.query).toBe('android workshop');
    expect(response.body.results.length).toBeGreaterThan(0);

    for (const result of response.body.results) {
      expect(result.event).toBeTruthy();
      expect(typeof result.score).toBe('number');
      expect(result.score).toBeGreaterThanOrEqual(0);
      expect(result.score).toBeLessThanOrEqual(1);
      expect(typeof result.reason).toBe('string');
    }
  });

  it('ranks the strongest title match first', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'android workshop' });
    expect(response.body.results[0].event.id).toBe('evt_001');
  });

  it('finds events by category synonym', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'concert' });
    expect(response.body.results.map((r) => r.event.id)).toContain('evt_002');
  });

  it('finds events by tag', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'robotics' });
    expect(response.body.results.map((r) => r.event.id)).toContain('evt_007');
  });

  it('finds an event from a fragment of a single title word', async () => {
    for (const query of ['work', 'worksho', 'andro']) {
      const response = await api().post('/api/ai/search').send({ query });
      expect(response.body.results.map((r) => r.event.id), `query: ${query}`).toContain('evt_001');
    }
  });

  it('finds an event from a partially typed multi-word title', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'android dev work' });
    expect(response.body.results[0].event.id).toBe('evt_001');
  });

  it('works anonymously', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'music' });
    expect(response.status).toBe(200);
    expect(response.body.results.length).toBeGreaterThan(0);
  });

  it('honours the limit parameter', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'campus', limit: 3 });
    expect(response.body.results.length).toBeLessThanOrEqual(3);
  });

  it('rejects a missing query with 400', async () => {
    const response = await api().post('/api/ai/search').send({});
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects a blank query with 400', async () => {
    const response = await api().post('/api/ai/search').send({ query: '   ' });
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('rejects an out-of-range limit with 400', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'music', limit: 999 });
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('personalizes results for an authenticated user', async () => {
    const email = `${unique('ai')}@uni.edu`;
    const { token } = (
      await api()
        .post('/api/auth/register')
        .send({ name: 'AI User', email, password: 'secret123', interests: ['music'] })
    ).body;

    const response = await as(token).post('/api/ai/search').send({ query: 'evening' });

    expect(response.status).toBe(200);
    const music = response.body.results.find((r) => r.event.id === 'evt_002');
    expect(music).toBeTruthy();
    expect(music.reason).toMatch(/interest/i);
  });

  it('personalizes for the seeded demo user via their bookmarks and interests', async () => {
    const { token } = await loginDemo();
    const response = await as(token).post('/api/ai/search').send({ query: 'android' });

    expect(response.body.results[0].event.id).toBe('evt_001');
    expect(response.body.results[0].reason).toMatch(/interest|bookmark/i);
  });
});

describe('POST /api/ai/search relevance', () => {
  it('returns an empty array for a query with no relevant matches', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'zzzz qqqq' });

    expect(response.status).toBe(200);
    expect(response.body.query).toBe('zzzz qqqq');
    expect(response.body.results).toEqual([]);
  });

  it('does not return the whole catalogue for a nonsense query', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'zzzz qqqq' });
    const { body: catalogue } = await api().get('/api/events');

    expect(response.body.results.length).toBeLessThan(catalogue.events.length);
  });

  it('excludes popular events that have no textual match', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'zzzz qqqq' });
    const ids = response.body.results.map((r) => r.event.id);

    // evt_003 (420/500) and evt_012 (480/600) are the most popular events and
    // must not surface for an unrelated query.
    expect(ids).not.toContain('evt_003');
    expect(ids).not.toContain('evt_012');
  });

  it('returns only events with real textual evidence for the query', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'robotics' });
    const ids = response.body.results.map((r) => r.event.id);

    expect(ids).toContain('evt_003');
    expect(ids).toContain('evt_007');
    expect(ids).not.toContain('evt_004');
  });

  it('does not saturate strong matches at a score of 1', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'android workshop' });

    for (const result of response.body.results) {
      expect(result.score).toBeLessThan(1);
    }
  });

  it('keeps scores in descending order and preserves ranking separation', async () => {
    const response = await api().post('/api/ai/search').send({ query: 'tech' });
    const scores = response.body.results.map((r) => r.score);

    expect([...scores].sort((a, b) => b - a)).toEqual(scores);
    expect(new Set(scores).size).toBeGreaterThan(1);
  });

  it('only admits interest-based matches for an authenticated user', async () => {
    const email = `${unique('rel')}@uni.edu`;
    const { token } = (
      await api()
        .post('/api/auth/register')
        .send({ name: 'Rel User', email, password: 'secret123', interests: ['music'] })
    ).body;

    const response = await as(token).post('/api/ai/search').send({ query: 'zzzz qqqq' });
    const ids = response.body.results.map((r) => r.event.id);

    expect(ids).toContain('evt_002');
    expect(ids).not.toContain('evt_003');
  });
});

describe('GET /api/ai/recommendations', () => {
  it('requires a JWT', async () => {
    const response = await api().get('/api/ai/recommendations');
    expect(response.status).toBe(401);
    expect(response.body.code).toBe('AUTH_REQUIRED');
  });

  it('returns personalized recommendations for the demo user', async () => {
    const { token } = await loginDemo();
    const response = await as(token).get('/api/ai/recommendations');

    expect(response.status).toBe(200);
    expect(response.body.recommendations.length).toBeGreaterThan(0);

    for (const rec of response.body.recommendations) {
      expect(rec.event).toBeTruthy();
      expect(typeof rec.score).toBe('number');
      expect(typeof rec.reason).toBe('string');
    }
  });

  it('returns recommendations sorted by descending score', async () => {
    const { token } = await loginDemo();
    const { body } = await as(token).get('/api/ai/recommendations');
    const scores = body.recommendations.map((r) => r.score);

    expect([...scores].sort((a, b) => b - a)).toEqual(scores);
  });

  it('reflects updated interests in the recommendation reasons', async () => {
    const email = `${unique('rec')}@uni.edu`;
    const auth = await api()
      .post('/api/auth/register')
      .send({ name: 'Rec User', email, password: 'secret123' });

    const token = auth.body.token;
    await as(token).put('/api/auth/me/interests').send({ interests: ['poetry'] });

    const response = await as(token).get('/api/ai/recommendations');
    const poetry = response.body.recommendations.find((r) => r.event.id === 'evt_009');

    expect(poetry).toBeTruthy();
    expect(poetry.reason).toMatch(/poetry/i);
  });
});
describe('GET /api/ai/recommendations eligibility', () => {
  /** Registers an authenticated user with the given interests. */
  async function userWith(interests) {
    const email = `${unique('elig')}@uni.edu`;
    const { body } = await api()
      .post('/api/auth/register')
      .send({ name: 'Eligibility', email, password: 'secret123', interests });
    return body.token;
  }

  async function createEvent(overrides = {}) {
    const { token } = await loginDemo();
    const response = await as(token).post('/api/events').send({
      title: unique('Cold Start'),
      category: 'Wellness',
      venue: 'Room 205',
      date: '2027-11-01',
      capacity: 40,
      ...overrides
    });
    return response.body.event;
  }

  it('recommends an interest match that has zero registrations', async () => {
    const event = await createEvent({ title: unique('Zero Reg Yoga'), tags: ['yoga'] });
    expect(event.registered).toBe(0);

    const token = await userWith(['yoga']);
    const { body } = await as(token).get('/api/ai/recommendations');

    const recommended = body.recommendations.find((rec) => rec.event.id === event.id);
    expect(recommended, 'zero-registration interest match must be recommended').toBeTruthy();
    expect(recommended.reason).toMatch(/interest/i);
  });

  it('recommends an interest match with very few registrations', async () => {
    const event = await createEvent({ title: unique('Low Reg'), registered: 1 });
    expect(event.registered).toBe(1);

    const token = await userWith(['wellness']);
    const { body } = await as(token).get('/api/ai/recommendations');

    const recommended = body.recommendations.find((rec) => rec.event.id === event.id);
    expect(recommended).toBeTruthy();
  });

  it('does not exclude events just because they score at or below the old threshold', async () => {
    const token = await userWith(['competitive']);
    const { body } = await as(token).get('/api/ai/recommendations');

    for (const rec of body.recommendations) {
      expect(rec.score).toBeGreaterThan(0);
      expect(rec.score).toBeLessThanOrEqual(1);
    }
  });

  it('ranks interest matches above events with no personalization signal', async () => {
    const token = await userWith(['poetry']);
    const { body } = await as(token).get('/api/ai/recommendations');

    const poetry = body.recommendations.findIndex((rec) => rec.event.id === 'evt_009');
    const unmatchable = body.recommendations.findIndex((rec) => rec.reason === 'Suggested for you');

    expect(poetry).toBeGreaterThanOrEqual(0);
    if (unmatchable >= 0) expect(poetry).toBeLessThan(unmatchable);
  });

  it('never claims the user registered for an event', async () => {
    const token = await userWith(['tech', 'ai', 'android']);
    const { body } = await as(token).get('/api/ai/recommendations');

    for (const rec of body.recommendations) {
      expect(rec.reason).not.toMatch(/registered|attending|you signed up/i);
    }
  });

  it('reflects a bookmark as the reason', async () => {
    const event = await createEvent({ title: unique('Bookmarked Cold'), tags: ['meditation'] });
    const token = await userWith([]);

    await as(token).post(`/api/auth/me/bookmarks/${event.id}`);
    const { body } = await as(token).get('/api/ai/recommendations');

    const recommended = body.recommendations.find((rec) => rec.event.id === event.id);
    expect(recommended?.reason).toMatch(/bookmark/i);
  });

  it('does not let popularity outrank an interest match', async () => {
    const token = await userWith(['competitive']);
    const { body } = await as(token).get('/api/ai/recommendations');

    // evt_012 (Career Fair, 480/600) is the most popular event in the catalogue.
    const matchIndex = body.recommendations.findIndex((rec) =>
      /interest/i.test(rec.reason)
    );
    const popularIndex = body.recommendations.findIndex(
      (rec) => rec.event.id === 'evt_012' && !/interest/i.test(rec.reason)
    );

    if (matchIndex >= 0 && popularIndex >= 0) {
      expect(matchIndex).toBeLessThan(popularIndex);
    }
  });

  it('never reports a score of exactly 1', async () => {
    const { token } = await loginDemo();
    const { body } = await as(token).get('/api/ai/recommendations');

    for (const rec of body.recommendations) {
      expect(rec.score).toBeLessThan(1);
    }
  });
});
