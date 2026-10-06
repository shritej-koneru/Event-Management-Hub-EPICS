/**
 * Live REST smoke test. MUTATES the target (registers users, creates events).
 *
 *   VERIFY_BASE_URL=http://localhost:3100 npm run verify:rest
 *
 * There is deliberately no default URL and no positional argument: this script
 * writes to whatever it is pointed at, and the conventional `localhost:3000`
 * development server is exactly the instance holding real data. The target must
 * be an explicit throwaway server on a scratch database - see
 * scripts/lib/requireScratchTarget.mjs for the guard and the escape hatch.
 */
import { requireScratchTarget } from './lib/requireScratchTarget.mjs';

const BASE = await requireScratchTarget();
if (!BASE) process.exit(2);

async function req(method, path, { body, token } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
  return { status: res.status, json, headers: res.headers, text };
}

let failures = 0;
function check(label, ok, detail) {
  if (!ok) failures += 1;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
}

console.log('\n=== 1. ping ===');
const ping = await req('GET', '/ping');
check('200 OK', ping.status === 200);
check('db connected', ping.json.database === 'connected', ping.json.database);
check('14 venues', ping.json.venues.length === 14, `${ping.json.venues.length}`);
check('security headers present', Boolean(ping.headers.get('content-security-policy')));

console.log('\n=== 2. AI search relevance ===');
const junk = await req('POST', '/api/ai/search', { body: { query: 'zzzz qqqq' } });
check('nonsense query -> 200', junk.status === 200);
check('nonsense query -> empty results', junk.json.results.length === 0, `${junk.json.results.length} results`);

const strong = await req('POST', '/api/ai/search', { body: { query: 'android workshop' } });
const top = strong.json.results[0];
check('strong query returns results', strong.json.results.length > 0, `${strong.json.results.length}`);
check('top result is evt_001', top?.event.id === 'evt_001', top?.event.id);
check('no score saturates at 1', strong.json.results.every((r) => r.score < 1),
  `max=${Math.max(...strong.json.results.map((r) => r.score))}`);
check('scores descending', strong.json.results.every((r, i, a) => i === 0 || a[i - 1].score >= r.score));
check('every result has a reason', strong.json.results.every((r) => typeof r.reason === 'string' && r.reason));

console.log('\n=== 3. pagination ===');
const all = await req('GET', '/api/events');
const p1 = await req('GET', '/api/events?page=1&pageSize=5');
const p2 = await req('GET', '/api/events?page=2&pageSize=5');
check('unpaginated returns all events', all.json.events.length >= 12, `${all.json.events.length}`);
check('no pagination key when unpaginated', all.json.pagination === undefined);
check('page 1 size', p1.json.events.length === 5);
check('pagination metadata', p1.json.pagination.page === 1 && p1.json.pagination.hasMore === true,
  JSON.stringify(p1.json.pagination));
check('pages disjoint', !p1.json.events.some((e) => p2.json.events.some((x) => x.id === e.id)));
check('total consistent', p1.json.pagination.total === p2.json.pagination.total, `${p1.json.pagination.total}`);
check('Android EventListResponse(events) intact',
  all.json.events.every((e) => 'id' in e && 'venueId' in e && 'tags' in e && 'icon' in e));

console.log('\n=== 4. filters ===');
const cat = await req('GET', '/api/events?category=TECH');
check('case-insensitive category', cat.json.events.length > 0 && cat.json.events.every((e) => e.category === 'Tech'),
  `${cat.json.events.length}`);
const byId = await req('GET', '/api/events?venue=room-204');
const byName = await req('GET', '/api/events?venue=Room%20204');
check('venue by id', byId.json.events.length > 0, `${byId.json.events.length}`);
check('venue by display name matches id',
  JSON.stringify(byId.json.events.map((e) => e.id)) === JSON.stringify(byName.json.events.map((e) => e.id)));
const combo = await req('GET', '/api/events?category=tech&venue=main-hall&q=tech');
check('combined filters', combo.json.events.length > 0 &&
  combo.json.events.every((e) => e.category === 'Tech' && e.venueId === 'main-hall'),
  combo.json.events.map((e) => e.id).join(','));
const typo = await req('GET', '/api/events?catgeory=Tech');
check('strict query rejects typo -> 400', typo.status === 400 && typo.json.code === 'VALIDATION_ERROR');

console.log('\n=== 5. event route endpoint ===');
const route = await req('GET', '/api/events/evt_001/route');
check('route 200', route.status === 200);
check('route ends at event venue', route.json.nodes.at(-1).id === 'room-204', route.json.nodes.at(-1).id);
check('route starts at entrance by default', route.json.nodes[0].id === 'entrance');
check('route has steps + distance', Array.isArray(route.json.steps) && route.json.totalDistanceM > 0);
const viaPath = await req('POST', '/api/navigation/path', { body: { from: 'entrance', to: 'Room 204' } });
check('matches POST /navigation/path shape',
  JSON.stringify(route.json.nodes.map((n) => n.id)) === JSON.stringify(viaPath.json.nodes.map((n) => n.id)));
const badFrom = await req('GET', '/api/events/evt_001/route?from=Space%20Elevator');
check('unknown from -> 400 UNKNOWN_VENUE', badFrom.status === 400 && badFrom.json.code === 'UNKNOWN_VENUE');

console.log('\n=== 6. auth + bookmarks ===');
const email = `live-${Date.now().toString(36)}@uni.edu`;
const reg = await req('POST', '/api/auth/register', { body: { name: 'Live User', email, password: 'secret123', interests: ['tech'] } });
check('register 201', reg.status === 201);
const token = reg.json.token;
check('register leaks no hash', !JSON.stringify(reg.json).match(/\$2[aby]\$/));

const anonCreate = await req('POST', '/api/events', { body: { title: 'Anon', category: 'Tech', venue: 'Room 205', date: '2028-01-01', capacity: 5 } });
check('anonymous event creation -> 401', anonCreate.status === 401 && anonCreate.json.code === 'AUTH_REQUIRED');

const created = await req('POST', '/api/events', {
  token,
  body: { title: `Live Event ${Date.now().toString(36)}`, category: 'Tech', venue: 'Room 205', date: '2028-01-01', capacity: 5 }
});
check('authenticated creation -> 201', created.status === 201, created.json?.error ?? '');
const liveId = created.json.event.id;

const bmAdd = await req('POST', `/api/auth/me/bookmarks/${liveId}`, { token });
check('bookmark add 200', bmAdd.status === 200 && bmAdd.json.bookmarked === true);
check('bookmarks contains event', bmAdd.json.bookmarks.includes(liveId));
const bmAgain = await req('POST', `/api/auth/me/bookmarks/${liveId}`, { token });
check('bookmark add idempotent', bmAgain.status === 200 &&
  bmAgain.json.bookmarks.filter((id) => id === liveId).length === 1);
const me = await req('GET', '/api/auth/me', { token });
check('GET /me includes bookmark', me.json.user.bookmarks.includes(liveId));
const bmRm = await req('DELETE', `/api/auth/me/bookmarks/${liveId}`, { token });
check('bookmark delete 200', bmRm.status === 200 && bmRm.json.bookmarked === false);
check('bookmark delete idempotent', (await req('DELETE', `/api/auth/me/bookmarks/${liveId}`, { token })).status === 200);
const bm404 = await req('POST', '/api/auth/me/bookmarks/evt_nope', { token });
check('unknown event bookmark -> 404', bm404.status === 404 && bm404.json.code === 'EVENT_NOT_FOUND');
check('bookmark requires auth', (await req('POST', `/api/auth/me/bookmarks/${liveId}`)).status === 401);

console.log('\n=== 7. recommendations ===');
const recs = await req('GET', '/api/ai/recommendations', { token });
check('recommendations 200', recs.status === 200 && recs.json.recommendations.length > 0);
const scores = recs.json.recommendations.map((r) => r.score);
check('recommendations descending', scores.every((s, i, a) => i === 0 || a[i - 1] >= s));
check('no recommendation claims registration',
  recs.json.recommendations.every((r) => !/registered|attending/i.test(r.reason)));
check('interest match present for tech user',
  recs.json.recommendations.some((r) => /interest/i.test(r.reason)));

console.log('\n=== 8. error hygiene ===');
const dup = await req('POST', '/api/auth/register', { body: { name: 'Dup', email, password: 'secret123' } });
check('duplicate -> 409 EMAIL_TAKEN', dup.status === 409 && dup.json.code === 'EMAIL_TAKEN');
check('no constraint leak', !/users_email_key|Key \(/i.test(dup.text), dup.json.error);
const e404 = await req('GET', '/api/events/evt_nope');
check('unknown event -> 404 EVENT_NOT_FOUND', e404.status === 404 && e404.json.code === 'EVENT_NOT_FOUND');
check('error envelope: error is a string', typeof e404.json.error === 'string' && typeof e404.json.code === 'string');

console.log(`\n${failures === 0 ? 'ALL LIVE CHECKS PASSED' : `${failures} LIVE CHECK(S) FAILED`}`);
process.exit(failures === 0 ? 0 : 1);