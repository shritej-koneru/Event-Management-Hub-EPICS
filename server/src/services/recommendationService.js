import { normalise, personalizationSignal } from '../ai/scoring.js';
import { allEvents, loadUserProfile } from './profileService.js';

const RECOMMENDATION_LIMIT = 8;

/**
 * Signal weights.
 *
 * Personalization dominates. `fill` (how full an event is) is deliberately
 * tiny: it is a tie-breaker between otherwise equal matches, not a ranking
 * driver. The previous implementation let popularity contribute a quarter of
 * the score and excluded anything at or below 0.3 outright, which meant brand
 * new and low-registration events could never be recommended.
 */
const W = {
  interest: 3,
  bookmark: 2.5,
  history: 1.5,
  discover: 0.5,
  fill: 0.25
};

/** Reasons must only describe signals that genuinely exist for this user. */
function reasonFor(signal, hasSignal) {
  if (signal.reason) return signal.reason;
  return hasSignal ? 'Matches your profile' : 'Suggested for you';
}

/**
 * GET /ai/recommendations — deterministic personalization, no ML.
 *
 * Ranking = per-user signal (interests / bookmarks / recent searches), with a
 * small fill-ratio tie-breaker. Events with no personalization signal are still
 * eligible but always sort below anything the user actually matched, so the
 * feed still shows fresh events without crowding out real matches.
 *
 * Every result carries a truthful `reason` so the Android Home screen can
 * explain itself. No result ever claims the user registered for an event:
 * registrations live on the device, not on the server.
 *
 * @param {{uid: string}} auth JWT claims of the current user
 */
export async function recommendFor(auth) {
  const [events, profile] = await Promise.all([allEvents(), loadUserProfile(auth)]);

  const scored = events.map((event) => {
    const signal = personalizationSignal(event, profile);
    const fill = event.registered / Math.max(event.capacity, 1);
    const hasSignal = Boolean(signal.kind);

    const weight = hasSignal ? W[signal.kind] : W.discover;
    const score = normalise(weight + fill * W.fill);

    return {
      event,
      score,
      hasSignal,
      reason: reasonFor(signal, hasSignal)
    };
  });

  return scored
    .sort(
      (a, b) =>
        Number(b.hasSignal) - Number(a.hasSignal) ||
        b.score - a.score ||
        a.event.date.localeCompare(b.event.date)
    )
    .slice(0, RECOMMENDATION_LIMIT)
    .map(({ event, score, reason }) => ({ event, score, reason }));
}