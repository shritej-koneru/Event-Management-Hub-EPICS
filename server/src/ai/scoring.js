import { CATEGORY_SYNONYMS, tokenize } from './tokenizer.js';

/**
 * Relevance scoring. Deterministic and explainable: each contribution is
 * additive so a result can always be justified in the `reason` field.
 *
 * Weights: title 4, tags 3, category 3, description 2, synonym 2.5, host 1.5.
 *
 * Two kinds of signal are tracked separately:
 *
 *   textRaw     - evidence the event's own words match the query.
 *   popularity  - how full the event is. This is a tie-breaker ONLY; it must
 *                 never be the reason an event appears in search results,
 *                 otherwise every event scores > 0 and a query matching
 *                 nothing returns the whole catalogue.
 *
 * `MIN_TEXT_RAW` is the relevance floor: below it an event has no textual
 * relationship to the query at all and search must not return it.
 */

const W = {
  title: 4,
  tag: 3,
  category: 3,
  description: 2,
  synonym: 2.5,
  host: 1.5,
  popularity: 0.5,
  titleBoost: 0.2
};

/**
 * Saturation constant for the score curve. `x / (x + K)` is monotonic and
 * approaches 1 asymptotically, so strong matches stay distinguishable instead
 * of all pinning at exactly 1.0 (which the previous raw/MAX_RAW division did).
 */
const SCORE_K = 6;

/** Minimum textual evidence required before an event may appear in results. */
export const MIN_TEXT_RAW = 0.5;

/** Maps an unbounded positive score onto the contract's 0..1 range. */
export function normalise(value) {
  if (!(value > 0)) return 0;
  return Number((value / (value + SCORE_K)).toFixed(4));
}

/**
 * Scores one event against tokenized query terms.
 *
 * @returns {{textRaw: number, popularity: number, raw: number, normalised: number,
 *            matches: boolean, fields: string[]}}
 *   `matches` is the authoritative relevance decision, and is deliberately
 *   independent of popularity.
 */
export function scoreEvent(event, queryTerms) {
  const title = tokenize(event.title);
  const description = tokenize(event.description);
  const host = tokenize(event.host);
  const tags = (event.tags ?? []).map((tag) => String(tag).toLowerCase());
  const category = String(event.category).toLowerCase();
  const synonyms = CATEGORY_SYNONYMS[category] || [];

  let textRaw = 0;
  let titleHit = false;
  const fields = new Set();

  for (const term of queryTerms) {
    if (title.includes(term)) {
      textRaw += W.title;
      titleHit = true;
      fields.add('title');
    } else if (description.includes(term)) {
      textRaw += W.description;
      fields.add('description');
    }
    if (host.includes(term)) {
      textRaw += W.host;
      fields.add('host');
    }
    if (term === category) {
      textRaw += W.category;
      fields.add('category');
    }
    if (synonyms.includes(term)) {
      textRaw += W.synonym;
      fields.add('synonym');
    }
    if (tags.includes(term)) {
      textRaw += W.tag;
      fields.add('tags');
    }
  }

  if (titleHit) {
    textRaw += W.titleBoost;
    fields.add('title');
  }

  // Popularity is deliberately kept out of the relevance decision.
  const popularity = (event.registered / Math.max(event.capacity, 1)) * W.popularity;

  return {
    textRaw: Number(textRaw.toFixed(4)),
    popularity: Number(popularity.toFixed(4)),
    raw: Number((textRaw + popularity).toFixed(4)),
    normalised: normalise(textRaw + popularity),
    matches: textRaw >= MIN_TEXT_RAW,
    fields: [...fields]
  };
}

/**
 * Personalization signals for a signed-in user: interests, bookmarks and
 * recent searches.
 *
 * `kind` names the strongest signal that fired so callers can weight it and
 * explain it honestly. Reasons only ever describe data that actually exists —
 * there is deliberately no "you registered for this" branch, because
 * registrations live on the device (Android OfflineStore) and the server has
 * no registration history.
 *
 * @returns {{boost: number, reason: string, kind: ('interest'|'bookmark'|'history'|null)}}
 */
export function personalizationSignal(event, profile) {
  if (!profile) return { boost: 0, reason: '', kind: null };

  const tags = (event.tags ?? []).map((tag) => String(tag).toLowerCase());
  const category = String(event.category).toLowerCase();
  const title = String(event.title).toLowerCase();

  const interestHit = (profile.interests || []).find((interest) => {
    const needle = String(interest).toLowerCase();
    return tags.includes(needle) || category === needle || title.includes(needle);
  });
  if (interestHit) {
    return {
      boost: 1.5,
      kind: 'interest',
      reason: `Matches your interest in: ${interestHit}`
    };
  }

  if ((profile.bookmarkedEvents || []).includes(event.id)) {
    return { boost: 1.5, kind: 'bookmark', reason: 'You bookmarked this event' };
  }

  const recentTerms = (profile.recentQueries || []).flatMap((query) => tokenize(query));
  const similarity = recentTerms.some(
    (term) => tags.includes(term) || category === term || title.includes(term)
  );
  if (similarity) {
    return { boost: 1.0, kind: 'history', reason: 'Similar to your recent searches' };
  }

  return { boost: 0, reason: '', kind: null };
}

/**
 * Combines textual relevance and personalization into the contract's 0..1 score.
 * The personalization boost can promote an event but never rescue an unrelated
 * one on its own - callers gate on `scoreEvent().matches` first.
 */
export function finalScore(textRaw, popularity, boost = 0) {
  return normalise(Number(textRaw) + Number(popularity || 0) + Number(boost || 0));
}

export { SCORE_K, W as WEIGHTS };