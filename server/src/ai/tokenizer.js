/**
 * Deterministic text processing for search. No external model, no network —
 * ranking is fully reproducible and explainable via `reason`.
 */

const STOP_WORDS = new Set([
  'a', 'an', 'the', 'for', 'and', 'or', 'to', 'of', 'in', 'on', 'at', 'with',
  'your', 'my', 'is', 'are', 'be', 'i', 'me', 'any', 'some', 'show', 'find'
]);

/** Lowercases, strips punctuation, drops stopwords and single characters. */
export function tokenize(text = '') {
  return String(text)
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, ' ')
    .split(/[\s-]+/)
    .filter((token) => token.length > 1 && !STOP_WORDS.has(token));
}

/**
 * Maps free-form search words onto the seeded categories so a query like
 * "concert" still surfaces Entertainment events.
 */
export const CATEGORY_SYNONYMS = {
  tech: ['technology', 'coding', 'software', 'developer', 'android', 'ai', 'robotics', 'app', 'code'],
  entertainment: ['music', 'concert', 'party', 'show', 'band', 'live'],
  arts: ['drama', 'poetry', 'theatre', 'theater', 'photography', 'art', 'music'],
  wellness: ['yoga', 'meditation', 'health', 'relax', 'fitness', 'mindfulness'],
  competitive: ['contest', 'hackathon', 'competition', 'pitch', 'coding'],
  career: ['job', 'jobs', 'internship', 'networking', 'placement', 'recruiter', 'career']
};

/** Resolves a query to the categories it plausibly refers to. */
export function inferCategories(queryTerms) {
  const matches = [];
  for (const [category, synonyms] of Object.entries(CATEGORY_SYNONYMS)) {
    if (queryTerms.includes(category) || queryTerms.some((term) => synonyms.includes(term))) {
      matches.push(category);
    }
  }
  return matches;
}