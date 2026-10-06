/**
 * Event Details verification, run against a live server.
 *
 * Walks EVERY event the API returns and asserts the details contract holds for
 * each one, then checks the invariants that only show up across the whole set:
 * announcement isolation between events sharing a venue, and the absence of any
 * invented statistic.
 *
 *   VERIFY_BASE_URL=http://localhost:3100 npm run verify:events
 *
 * Read-only apart from minting a throwaway verifier token, which needs a JWT to
 * prove the authenticated `isBookmarked` behaviour - so it still writes a user
 * row, and is guarded exactly like the other live verifiers. There is no default
 * URL and no positional argument; see scripts/lib/requireScratchTarget.mjs.
 */
import { requireScratchTarget } from './lib/requireScratchTarget.mjs';

const base = await requireScratchTarget();
if (!base) process.exit(2);
const results = [];
let failures = 0;

function check(name, condition, extra) {
  const ok = Boolean(condition);
  if (!ok) failures += 1;
  results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && extra !== undefined ? ` -> ${JSON.stringify(extra)}` : ''}`);
}

const get = async (path) => {
  const res = await fetch(`${base}${path}`);
  let body;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  return { status: res.status, body };
};

async function verifierToken() {
  const email = `event-verify-${Date.now().toString(36)}@uni.edu`;
  const res = await fetch(`${base}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Event Verifier', email, password: 'secret123' })
  });
  const body = await res.json();
  if (!body.token) throw new Error(`could not mint verifier token: ${JSON.stringify(body)}`);
  return body.token;
}

/* -------------------------------------------------------------------------- */
/* Catalogue-wide                                                              */
/* -------------------------------------------------------------------------- */

const ping = await get('/ping');
check('ping responds', ping.status === 200, ping.body);
check('ping advertises contract 1.1.0', ping.body?.contractVersion === '1.1.0', ping.body?.contractVersion);

const list = await get('/api/events');
check('event list responds', list.status === 200, list.body);
const events = list.body?.events ?? [];
check('catalogue is not empty', events.length > 0, events.length);
results.push(`      (walking ${events.length} events)`);

/* -------------------------------------------------------------------------- */
/* Per-event contract                                                          */
/* -------------------------------------------------------------------------- */

const detailsByEvent = new Map();
const announcementsByEvent = new Map();

for (const event of events) {
  const { id, venueId } = event;
  const tag = `[${id}]`;

  const single = await get(`/api/events/${id}`);
  check(`${tag} GET /events/:id -> 200`, single.status === 200, single.body);
  check(
    `${tag} GET /events/:id keeps the { event } envelope`,
    Object.keys(single.body ?? {}).length === 1 && Boolean(single.body?.event),
    Object.keys(single.body ?? {})
  );

  const details = await get(`/api/events/${id}/details`);
  check(`${tag} GET /events/:id/details -> 200`, details.status === 200, details.body);
  detailsByEvent.set(id, details.body);

  if (details.status !== 200) continue;
  const d = details.body;

  check(
    `${tag} details has the six documented sections`,
    ['announcements', 'charts', 'event', 'navigation', 'statistics', 'venue'].every((k) => k in d),
    Object.keys(d)
  );

  // The canonical Event shape must not be forked by the aggregate endpoint.
  check(`${tag} details.event is identical to GET /events/:id`, JSON.stringify(d.event) === JSON.stringify(event), {
    details: d.event,
    single: event
  });

  // No start/end date-time concept anywhere in the payload.
  const serialized = JSON.stringify(d).toLowerCase();
  const forbidden = ['startdate', 'starttime', 'enddate', 'endtime'].filter((k) => serialized.includes(k));
  check(`${tag} no startDate/startTime/endDate/endTime fields`, forbidden.length === 0, forbidden);
  check(`${tag} keeps canonical date + time`, /^\d{4}-\d{2}-\d{2}$/.test(d.event.date) && typeof d.event.time === 'string', {
    date: d.event.date,
    time: d.event.time
  });

  // Statistics must equal the stored columns, not a guess.
  const s = d.statistics;
  check(`${tag} statistics.capacity matches the event`, s.capacity === event.capacity, s.capacity);
  check(`${tag} statistics.registered matches the event`, s.registered === event.registered, s.registered);
  check(
    `${tag} seatsRemaining is capacity - registered`,
    s.seatsRemaining === Math.max(event.capacity - event.registered, 0),
    s.seatsRemaining
  );
  check(`${tag} occupancyPercent within 0..100`, s.occupancyPercent >= 0 && s.occupancyPercent <= 100, s.occupancyPercent);
  check(`${tag} bookmarkCount is a non-negative aggregate`, Number.isInteger(s.bookmarkCount) && s.bookmarkCount >= 0, s.bookmarkCount);
  check(`${tag} announcementCount matches the embedded feed`, s.announcementCount === d.announcements.length, {
    count: s.announcementCount,
    length: d.announcements.length
  });
  check(`${tag} isBookmarked is false when anonymous`, s.isBookmarked === false, s.isBookmarked);

  // Nothing may imply the caller personally registered.
  const statKeys = Object.keys(s).map((k) => k.toLowerCase());
  const personal = ['userregistered', 'isregistered', 'attended', 'myregistration', 'registeredme'].filter((k) => statKeys.includes(k));
  check(`${tag} no "you registered" claim`, personal.length === 0, personal);

  // Venue must be real graph data.
  check(`${tag} venue.id matches the event venue`, d.venue.id === venueId, { venue: d.venue?.id, venueId });
  check(`${tag} venue.navigationNode is the venue id`, d.venue.navigationNode === venueId, d.venue.navigationNode);
  check(`${tag} venue.neighbors is an array`, Array.isArray(d.venue.neighbors), d.venue.neighbors);

  // Navigation must terminate at the event's own venue.
  const nav = d.navigation;
  check(`${tag} navigation.from defaults to entrance`, nav.from === 'entrance', nav.from);
  check(`${tag} navigation starts at the entrance`, nav.nodes[0].id === 'entrance', nav.nodes[0]);
  check(`${tag} navigation ends at the event venue`, nav.nodes.at(-1).id === venueId, nav.nodes.at(-1));
  check(`${tag} navigation has real steps and distance`, nav.steps.length > 0 && nav.totalDistanceM > 0, {
    steps: nav.steps.length,
    distance: nav.totalDistanceM
  });

  // Charts: real points only, series must add up.
  check(`${tag} charts is an array`, Array.isArray(d.charts), d.charts);
  let chartsOk = true;
  for (const chart of d.charts) {
    const sum = chart.data.reduce((total, point) => total + point.value, 0);
    if (sum !== chart.total) {
      chartsOk = false;
      results.push(`FAIL  ${tag} chart ${chart.id} data sums to total -> sum=${sum} total=${chart.total}`);
    }
    for (const point of chart.data) {
      if (!Number.isFinite(point.value) || point.value < 0) chartsOk = false;
    }
  }
  check(`${tag} every chart series adds up to its own total`, chartsOk);
  check(
    `${tag} occupancy chart present only when capacity > 0`,
    event.capacity === 0 ? !d.charts.some((c) => c.id === 'occupancy') : d.charts.some((c) => c.id === 'occupancy'),
    d.charts.map((c) => c.id)
  );
  const trend = d.charts.find((c) => c.id === 'announcementTrend');
  check(
    `${tag} announcement trend only when it has 2+ points`,
    !trend || trend.data.length >= 2,
    trend?.data.length
  );

  // Announcements embedded here must belong to THIS event only.
  const wrongAnnouncements = d.announcements.filter((a) => a.eventId !== id);
  check(`${tag} embedded announcements all belong to this event`, wrongAnnouncements.length === 0, wrongAnnouncements);

  // The standalone event-scoped feed must agree.
  const scoped = await get(`/api/chat/announcements?eventId=${id}`);
  check(`${tag} GET /chat/announcements?eventId -> 200`, scoped.status === 200, scoped.body);
  check(`${tag} event-scoped feed echoes eventId`, scoped.body?.eventId === id, scoped.body?.eventId);
  const wrongScoped = (scoped.body?.announcements ?? []).filter((a) => a.eventId !== id);
  check(`${tag} event-scoped feed contains no other event's messages`, wrongScoped.length === 0, wrongScoped);
  announcementsByEvent.set(id, scoped.body?.announcements ?? []);

  const route = await get(`/api/events/${id}/route`);
  check(`${tag} GET /events/:id/route -> 200`, route.status === 200, route.body);
  check(`${tag} route targets the event venue`, route.body?.venueId === venueId, route.body?.venueId);
}

/* -------------------------------------------------------------------------- */
/* Cross-event invariants                                                      */
/* -------------------------------------------------------------------------- */

// The real reason eventId exists: co-located events must not share messages.
const byVenue = new Map();
for (const event of events) {
  byVenue.set(event.venueId, [...(byVenue.get(event.venueId) ?? []), event]);
}
const sharedVenues = [...byVenue.entries()].filter(([, list_]) => list_.length > 1);
results.push(`      (${sharedVenues.length} venue(s) host multiple events)`);

for (const [venueId, atVenue] of sharedVenues) {
  const sets = atVenue.map((e) => new Set((announcementsByEvent.get(e.id) ?? []).map((a) => a.message)));

  for (let i = 0; i < sets.length; i += 1) {
    for (let j = i + 1; j < sets.length; j += 1) {
      const overlap = [...sets[i]].filter((message) => sets[j].has(message));
      check(
        `[${venueId}] ${atVenue[i].id} and ${atVenue[j].id} share no announcement text`,
        overlap.length === 0,
        overlap
      );
    }
  }
}

// Category and total figures must agree with the real catalogue size.
const anyDetails = [...detailsByEvent.values()].find(Boolean);
check(
  'statistics.totalEvents equals the catalogue size',
  anyDetails?.statistics.totalEvents === events.length,
  { reported: anyDetails?.statistics.totalEvents, actual: events.length }
);

// Negative cases: a typo must be a 404, not a silently empty result.
const missing = 'evt_definitely_missing';
const missingDetails = await get(`/api/events/${missing}/details`);
check('unknown event -> 404 EVENT_NOT_FOUND on /details', missingDetails.status === 404 && missingDetails.body?.code === 'EVENT_NOT_FOUND', missingDetails.body);
const missingAnnouncements = await get(`/api/chat/announcements?eventId=${missing}`);
check('unknown event -> 404 EVENT_NOT_FOUND on announcements', missingAnnouncements.status === 404 && missingAnnouncements.body?.code === 'EVENT_NOT_FOUND', missingAnnouncements.body);
const badFrom = await get(`/api/events/${events[0].id}/details?from=nowhere`);
check('bad from node -> 400 UNKNOWN_VENUE', badFrom.status === 400 && badFrom.body?.code === 'UNKNOWN_VENUE', badFrom.body);

// The unfiltered venue-wide feed must still work.
const wide = await get('/api/chat/announcements');
check('unfiltered announcements feed still responds', wide.status === 200 && Array.isArray(wide.body?.announcements), wide.body);
check('unfiltered feed carries no eventId key', wide.body && wide.body.eventId === undefined, wide.body?.eventId);

/* -------------------------------------------------------------------------- */
/* Authenticated behaviour                                                     */
/* -------------------------------------------------------------------------- */

try {
  const token = await verifierToken();
  const res = await fetch(`${base}/api/events/${events[0].id}/details`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  const body = await res.json();
  check('authenticated details -> 200', res.status === 200, body);
  // A brand-new user has bookmarked nothing, so this must be false, not guessed.
  check('a user with no bookmarks sees isBookmarked=false', body?.statistics?.isBookmarked === false, body?.statistics?.isBookmarked);
} catch (err) {
  check('authenticated details check ran', false, err.message);
}

/* -------------------------------------------------------------------------- */

console.log(results.join('\n'));
console.log(`\n${results.filter((line) => line.startsWith('PASS')).length} passed, ${failures} failed`);
process.exit(failures > 0 ? 1 : 0);