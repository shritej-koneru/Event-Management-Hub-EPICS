import { finalScore, personalizationSignal, scoreEvent } from '../ai/scoring.js';
import { tokenize } from '../ai/tokenizer.js';
import { checkEvent, isEmptyFilters, parseSearchQuery } from '../ai/queryFilters.js';
import { allEvents, loadUserProfile, rememberSearch } from './profileService.js';

const FIELD_REASON = {
  title: 'Matches the event title',
  tags: 'Matches event tags',
  category: 'Matches the event category',
  description: 'Matches the event description',
  synonym: 'Related to the event category',
  host: 'Matches the host'
};

function describeMatch(fields, terms) {
  const named = fields.filter((field) => FIELD_REASON[field]);
  if (named.length) return FIELD_REASON[named[0]];
  return terms.length ? 'Matches your search' : 'Suggested for you';
}

/**
 * POST /ai/search
 *
 * Ranks events against a natural-language query using title, tags, category,
 * description, host and venue text, then re-ranks for a signed-in user using
 * interests, bookmarks and search history. Anonymous callers get the same
 * relevance ranking without personalization.
 *
 * Relevance gate: an event is only eligible when it has real textual evidence
 * for the query (`scoreEvent().matches`) or, for a signed-in user, a concrete
 * personalization signal. Popularity is a tie-breaker and can never on its own
 * put an event into the result set, so a query that matches nothing returns an
 * empty list rather than the whole catalogue.
 *
 * @returns {Promise<{ query: string, results: {event: object, score: number, reason: string}[] }>}
 */
export async function searchEvents({ query, limit = 10, auth = null, filters: explicit = null, today = todayIso() }) {
  const [events, profile] = await Promise.all([allEvents(), loadUserProfile(auth)]);

  await rememberSearch(auth, query);

  // Natural-language filters (date, price, accessibility, category) plus any the app sent explicitly. Explicit wins.
  const categories = [...new Set(events.map((e) => e.category))];
  const parsed = parseSearchQuery(query, today, categories);
  const filters = mergeFilters(parsed.filters, explicit);
  const filtered = !isEmptyFilters(filters);
  const terms = tokenize(filtered ? parsed.text : query);

  // Filters are strict. An event that does not DECLARE a price/accessibility value a filter needs is left out and counted.
  let candidates = events;
  let unknownExcluded = 0;
  if (filtered) {
    candidates = [];
    for (const event of events) {
      const check = checkEvent(event, filters);
      if (check.ok) candidates.push(event);
      else if (check.unknown) unknownExcluded += 1;
    }
  }

  const scored = candidates.map((event) => {
    const { textRaw, popularity, matches, fields } = scoreEvent(event, terms);
    const { boost, reason } = personalizationSignal(event, profile);

    return {
      event,
      score: finalScore(textRaw, popularity, boost),
      reason: reason || (filtered && !terms.length ? 'Matches your filters' : describeMatch(fields, terms)),
      // Personalization is a genuine match for that user, so it is admitted
      // alongside textual matches - but never for anonymous callers. With filters and no words left to match, passing
      // the filters is the match.
      eligible: matches || (profile !== null && boost > 0) || (filtered && terms.length === 0)
    };
  });

  const response = {
    query,
    results: scored
      .filter((entry) => entry.eligible)
      .sort((a, b) => b.score - a.score || a.event.date.localeCompare(b.event.date))
      .slice(0, limit)
      .map(({ event, score, reason }) => ({ event, score, reason }))
  };
  if (filtered) {
    response.appliedFilters = [...parsed.applied, ...describeExplicit(explicit)];
    response.unknownExcluded = unknownExcluded;
  }
  return response;
}

const todayIso = () => new Date().toISOString().slice(0, 10);

/** Explicit (UI) filters override anything parsed from the text; accessibility features are combined. */
function mergeFilters(parsed, explicit) {
  const merged = { ...parsed };
  for (const [key, value] of Object.entries(explicit ?? {})) {
    if (value === undefined || value === null) continue;
    if (key === 'accessibility') {
      merged.accessibility = [...new Set([...(parsed.accessibility ?? []), ...value])];
    } else {
      merged[key] = value;
    }
  }
  return merged;
}

/** Human-readable list of what the explicit filters were, so the app can show what was applied. */
function describeExplicit(explicit) {
  if (!explicit) return [];
  const out = [];
  if (explicit.category) out.push(`category ${explicit.category}`);
  if (explicit.dateFrom || explicit.dateTo) out.push(`dates ${explicit.dateFrom ?? '...'} to ${explicit.dateTo ?? '...'}`);
  if (explicit.free) out.push('free');
  if (explicit.maxPriceCents != null) out.push(`price up to ${explicit.maxPriceCents / 100}`);
  for (const key of explicit.accessibility ?? []) out.push(key);
  return out;
}