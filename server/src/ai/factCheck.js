/**
 * Guards for rewrites. A model asked to make an event description simpler may "helpfully" add amenities or
 * accessibility features. These checks reject an output that mentions such a feature when the SOURCE (the event's
 * own data) does not. They are deliberately conservative: a false alarm shows the original text, a miss would show an
 * invented claim, and the second is the worse failure.
 */

/** Feature words that are claims about accessibility. Allowed only when the event data supports them. */
const ACCESSIBILITY_TERMS = [
  'wheelchair', 'step-free', 'step free', 'ramp', 'elevator', 'lift', 'sign language', 'interpreter', 'captions',
  'subtitles', 'hearing loop', 'accessible', 'braille', 'quiet room', 'quiet space'
];

/** Amenity / commercial claims that must come from the source if they are mentioned at all. */
const AMENITY_TERMS = [
  'free entry', 'free admission', 'free food', 'free lunch', 'free snacks', 'free of charge', 'lunch', 'dinner', 'snacks',
  'refreshments', 'parking', 'wifi', 'wi-fi', 'certificate', 'swag', 'prize', 'prizes', 'refund', 'tickets are'
];

const normalise = (s) => String(s ?? '').toLowerCase().replace(/[‐-―]/g, '-');

/**
 * @param {string} sourceText  everything the model was allowed to use (original description, tags, explicit facts)
 * @param {string} outputText  the rewrite
 * @returns {string[]} the claim words the output makes that the source does not support (empty = fine)
 */
export function unsupportedClaims(sourceText, outputText) {
  const source = normalise(sourceText);
  const output = normalise(outputText);
  const bad = [];
  for (const term of [...ACCESSIBILITY_TERMS, ...AMENITY_TERMS]) {
    const pattern = new RegExp(`(^|[^a-z])${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}($|[^a-z])`);
    if (pattern.test(output) && !pattern.test(source)) bad.push(term);
  }
  return bad;
}

/**
 * The text a rewrite may draw on, as plain words: the event's own description and tags plus the EXPLICIT typed facts.
 * Accessibility features appear only when declared true; an unknown or false value contributes nothing.
 */
export function eventFactsText(event) {
  const parts = [event.title, event.category, event.venue, event.host, event.description, ...(event.tags ?? [])];
  if (event.price?.type === 'free') parts.push('free admission');
  if (event.price?.type === 'paid') parts.push(`costs ${(event.price.amountCents / 100).toFixed(2)} ${event.price.currency}`);
  const labels = {
    stepFree: 'step-free access',
    accessibleRestroom: 'accessible restroom',
    signLanguage: 'sign language interpretation',
    captions: 'captions',
    hearingLoop: 'hearing loop'
  };
  for (const [key, label] of Object.entries(labels)) if (event.accessibility?.[key] === true) parts.push(label);
  return parts.filter(Boolean).join(' . ');
}
