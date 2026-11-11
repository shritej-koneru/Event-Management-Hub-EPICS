import { findEventsByIds, listEvents } from '../models/eventModel.js';
import { listBookmarkIds } from '../models/bookmarkModel.js';
import { ApiError } from '../utils/errors.js';

/**
 * Schedule conflicts and alternatives. Deterministic: plain date/time arithmetic over events the server has, no model.
 *
 * DATA LIMITATION (shown to the user with every answer): registrations are kept on the device, not on the server. The
 * server only knows (a) the event ids the app sends from its own saved/registered list, or (b) the user's bookmarks on
 * the server. A registration that exists only on another device is not seen, so "no conflicts" means "no conflicts among
 * the events this check was given", never "your whole schedule is clear".
 */

const MAX_IDS = 30;
const MAX_ALTERNATIVES = 3;
const todayIso = () => new Date().toISOString().slice(0, 10);

/** Start/end in minutes on a single timeline. Dates and times are campus-local wall-clock values compared as-is. */
function interval(event) {
  const [y, m, d] = event.date.split('-').map(Number);
  const [hh, mm] = event.time.split(':').map(Number);
  const start = Date.UTC(y, m - 1, d, hh, mm) / 60000;
  return { start, end: start + event.durationMin };
}

const overlap = (a, b) => Math.min(a.end, b.end) - Math.max(a.start, b.start);

const brief = (event) => ({
  id: event.id, title: event.title, category: event.category, venue: event.venue, date: event.date, time: event.time, durationMin: event.durationMin
});

/** Pairs of [events] whose time ranges overlap (touching ends are not a conflict). Pure, for tests. */
export function findConflicts(events) {
  const withTimes = events.map((e) => ({ e, t: interval(e) }));
  const conflicts = [];
  for (let i = 0; i < withTimes.length; i += 1) {
    for (let j = i + 1; j < withTimes.length; j += 1) {
      const minutes = overlap(withTimes[i].t, withTimes[j].t);
      if (minutes > 0) conflicts.push({ a: brief(withTimes[i].e), b: brief(withTimes[j].e), overlapMinutes: Math.round(minutes) });
    }
  }
  return conflicts;
}

/**
 * Other events the person could attend instead of [target]: same category, still upcoming, not already saved, and
 * not overlapping anything else they have saved. Nearest in time first.
 */
export function findAlternatives(target, saved, catalogue, today = todayIso()) {
  const others = saved.filter((e) => e.id !== target.id).map(interval);
  const savedIds = new Set(saved.map((e) => e.id));
  const targetStart = interval(target).start;
  return catalogue
    .filter((e) => e.category === target.category && !savedIds.has(e.id) && e.date >= today)
    .filter((e) => {
      const t = interval(e);
      return others.every((o) => overlap(o, t) <= 0);
    })
    .sort((x, y) => Math.abs(interval(x).start - targetStart) - Math.abs(interval(y).start - targetStart))
    .slice(0, MAX_ALTERNATIVES)
    .map(brief);
}

const LIMITATION =
  'Registrations are stored on your device, so this check only covers the events listed in "basedOn". ' +
  'Anything you registered for on another device is not included.';

/**
 * @param {{ eventIds?: string[], auth: { uid: string } }} input
 *   eventIds: the events the app knows are saved/registered. Omitted: the user's bookmarks on the server.
 */
export async function checkSchedule({ eventIds, auth }, { today = todayIso() } = {}) {
  let ids;
  let source;
  if (Array.isArray(eventIds)) {
    ids = [...new Set(eventIds)];
    source = 'app';
  } else {
    ids = await listBookmarkIds(auth.uid);
    source = 'server-bookmarks';
  }
  if (ids.length > MAX_IDS) throw ApiError.badRequest(`Too many events (max ${MAX_IDS})`, 'INPUT_TOO_LONG');

  const found = ids.length ? await findEventsByIds(ids) : [];
  const foundIds = new Set(found.map((e) => e.id));
  const unknownIds = ids.filter((id) => !foundIds.has(id));

  const conflicts = findConflicts(found);
  let alternatives = [];
  if (conflicts.length) {
    const { events: catalogue } = await listEvents();
    const conflicted = new Map();
    for (const c of conflicts) for (const side of [c.a, c.b]) conflicted.set(side.id, found.find((e) => e.id === side.id));
    alternatives = [...conflicted.values()].map((target) => ({
      forEventId: target.id,
      options: findAlternatives(target, found, catalogue, today)
    }));
  }

  return {
    hasConflicts: conflicts.length > 0,
    conflicts,
    alternatives,
    basedOn: { source, eventCount: found.length, eventIds: found.map((e) => e.id), unknownIds },
    limitation: LIMITATION
  };
}
