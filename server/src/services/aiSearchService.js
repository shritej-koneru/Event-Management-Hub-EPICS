import { finalScore, personalizationSignal, scoreEvent } from '../ai/scoring.js';
import { tokenize } from '../ai/tokenizer.js';
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
export async function searchEvents({ query, limit = 10, auth = null }) {
  const terms = tokenize(query);
  const [events, profile] = await Promise.all([allEvents(), loadUserProfile(auth)]);

  await rememberSearch(auth, query);

  const scored = events.map((event) => {
    const { textRaw, popularity, matches, fields } = scoreEvent(event, terms);
    const { boost, reason } = personalizationSignal(event, profile);

    return {
      event,
      score: finalScore(textRaw, popularity, boost),
      reason: reason || describeMatch(fields, terms),
      // Personalization is a genuine match for that user, so it is admitted
      // alongside textual matches - but never for anonymous callers.
      eligible: matches || (profile !== null && boost > 0)
    };
  });

  return {
    query,
    results: scored
      .filter((entry) => entry.eligible)
      .sort((a, b) => b.score - a.score || a.event.date.localeCompare(b.event.date))
      .slice(0, limit)
      .map(({ event, score, reason }) => ({ event, score, reason }))
  };
}