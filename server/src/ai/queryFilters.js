import { ACCESSIBILITY_KEYS } from '../middleware/validators.js';

/**
 * Natural-language filters for event search. Deterministic: no model, no network, same input gives the same output.
 *
 * Two rules matter more than the parsing:
 *  - Price and accessibility filters match ONLY events that explicitly declare the value. An event whose price or
 *    accessibility is unknown never matches such a filter (it is counted in `unknownExcluded` so the caller can say so).
 *  - Nothing is inferred from an event's prose. "free" in a description does not make an event free.
 */

const DAY_MS = 24 * 60 * 60 * 1000;
const WEEKDAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

const ACCESSIBILITY_PHRASES = [
  { key: 'stepFree', label: 'step-free', pattern: /\b(step[\s-]?free|wheelchair(?:[\s-]accessible)?|no stairs|accessible)\b/ },
  { key: 'accessibleRestroom', label: 'accessible restroom', pattern: /\baccessible (?:restroom|toilet|washroom)s?\b/ },
  { key: 'signLanguage', label: 'sign language', pattern: /\bsign[\s-]language\b/ },
  { key: 'captions', label: 'captions', pattern: /\b(?:captions?|captioned|subtitles?|subtitled)\b/ },
  { key: 'hearingLoop', label: 'hearing loop', pattern: /\bhearing[\s-]loop\b/ }
];

const iso = (date) => date.toISOString().slice(0, 10);
const addDays = (date, n) => new Date(date.getTime() + n * DAY_MS);

/** Parses `today` ("YYYY-MM-DD") as a UTC date. */
function utc(today) {
  return new Date(`${today}T00:00:00Z`);
}

/**
 * Pulls date, price, accessibility and category filters out of a free-text query.
 *
 * @param {string} query
 * @param {string} today YYYY-MM-DD (passed in so tests are reproducible)
 * @param {string[]} knownCategories categories that exist in the catalogue (matched whole-word, case-insensitive)
 * @returns {{ text: string, filters: object, applied: string[] }}
 *   `text` is the query with the filter words removed (it may be empty); `applied` describes what was understood.
 */
export function parseSearchQuery(query, today, knownCategories = []) {
  let text = ` ${String(query ?? '').toLowerCase()} `;
  const filters = {};
  const applied = [];
  const take = (pattern) => {
    const match = text.match(pattern);
    if (match) text = text.replace(pattern, ' ');
    return match;
  };
  const now = utc(today);

  // ---- date --------------------------------------------------------------------------------------------------
  let m;
  if ((m = take(/\b(\d{4}-\d{2}-\d{2})\b/))) {
    filters.dateFrom = filters.dateTo = m[1];
    applied.push(`on ${m[1]}`);
  } else if (take(/\bthis weekend\b/)) {
    const toSaturday = (6 - now.getUTCDay() + 7) % 7;
    const saturday = addDays(now, toSaturday);
    filters.dateFrom = iso(now.getUTCDay() === 0 ? now : saturday);
    filters.dateTo = iso(now.getUTCDay() === 0 ? now : addDays(saturday, 1));
    applied.push('this weekend');
  } else if (take(/\bnext week\b/)) {
    const toNextMonday = ((8 - now.getUTCDay()) % 7) || 7;
    const monday = addDays(now, toNextMonday);
    filters.dateFrom = iso(monday);
    filters.dateTo = iso(addDays(monday, 6));
    applied.push('next week');
  } else if (take(/\bthis week\b/)) {
    filters.dateFrom = iso(now);
    filters.dateTo = iso(addDays(now, (7 - now.getUTCDay()) % 7));
    applied.push('this week');
  } else if (take(/\btomorrow\b/)) {
    filters.dateFrom = filters.dateTo = iso(addDays(now, 1));
    applied.push('tomorrow');
  } else if (take(/\btoday\b/)) {
    filters.dateFrom = filters.dateTo = iso(now);
    applied.push('today');
  } else {
    for (const [index, name] of WEEKDAYS.entries()) {
      if (take(new RegExp(`\\b(?:on |next )?${name}\\b`))) {
        const delta = (index - now.getUTCDay() + 7) % 7;
        filters.dateFrom = filters.dateTo = iso(addDays(now, delta));
        applied.push(`on ${name}`);
        break;
      }
    }
  }

  // ---- accessibility (explicit values only) ------------------------------------------------------------------
  // Specific phrases first so "accessible restroom" is not swallowed by the generic "accessible".
  for (const phrase of [...ACCESSIBILITY_PHRASES].reverse()) {
    if (take(phrase.pattern)) {
      filters.accessibility = [...new Set([...(filters.accessibility ?? []), phrase.key])];
      applied.push(phrase.label);
    }
  }

  // ---- price (explicit values only) --------------------------------------------------------------------------
  if ((m = take(/\b(?:under|below|less than|upto|up to|max(?:imum)?|within)\s+(?:rs\.?|inr|₹|\$)?\s*(\d{1,6})\b/))) {
    filters.maxPriceCents = Number(m[1]) * 100;
    applied.push(`price up to ${m[1]}`);
  }
  if (take(/\b(?:free of charge|free|no cost|no fee|gratis)\b/)) {
    filters.free = true;
    applied.push('free');
  }

  // ---- category ----------------------------------------------------------------------------------------------
  for (const category of knownCategories) {
    if (take(new RegExp(`\\b${category.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`))) {
      filters.category = category;
      applied.push(`category ${category}`);
      break;
    }
  }

  return { text: text.replace(/\s+/g, ' ').trim(), filters, applied };
}

/** Which filter keys the caller may pass explicitly (the app's filter chips). Anything else is rejected upstream. */
export const FILTER_KEYS = ['category', 'dateFrom', 'dateTo', 'free', 'maxPriceCents', 'accessibility'];

/** True when no filter is set. */
export function isEmptyFilters(filters) {
  return !filters || Object.values(filters).every((v) => v === undefined || v === null || (Array.isArray(v) && !v.length));
}

/**
 * Does [event] satisfy [filters]? Returns `{ ok, unknown }`: `unknown` is true when the ONLY reason it failed is that a
 * price or accessibility value the filter needs is not declared (so the caller can report "N excluded, info missing").
 */
export function checkEvent(event, filters) {
  let unknown = false;
  let ok = true;

  if (filters.category && event.category.toLowerCase() !== filters.category.toLowerCase()) ok = false;
  if (filters.dateFrom && event.date < filters.dateFrom) ok = false;
  if (filters.dateTo && event.date > filters.dateTo) ok = false;

  const price = event.price ?? null;
  if (filters.free) {
    if (!price) unknown = true;
    else if (price.type !== 'free') ok = false;
  }
  if (filters.maxPriceCents !== undefined && filters.maxPriceCents !== null) {
    if (!price) unknown = true;
    else if (price.type === 'paid' && price.amountCents > filters.maxPriceCents) ok = false;
  }

  for (const key of filters.accessibility ?? []) {
    const value = event.accessibility?.[key];
    if (value === undefined) unknown = true;
    else if (value !== true) ok = false;
  }

  if (!ok) return { ok: false, unknown: false };
  return unknown ? { ok: false, unknown: true } : { ok: true, unknown: false };
}

/** Accessibility keys the API accepts (re-exported so callers validate against one list). */
export { ACCESSIBILITY_KEYS };
