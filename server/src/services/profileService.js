import { listEvents } from '../models/eventModel.js';
import { findUserById } from '../models/userModel.js';
import { listBookmarkIds } from '../models/bookmarkModel.js';
import { recentQueries, recordSearch } from '../models/searchHistoryModel.js';

/**
 * Builds the personalization profile for the authenticated user.
 * Always resolves from the JWT `uid` claim — never from a hard-coded id.
 *
 * There is deliberately no `eventHistory` field: registrations are tracked on
 * the device by the Android client, so the server has no registration history
 * to read and must never imply it has one. Interests, bookmarks and recent
 * searches are the real signals.
 *
 * @param {{uid?: string}} [auth]
 * @returns {Promise<null | {interests: string[], bookmarkedEvents: string[], recentQueries: string[]}>}
 */
export async function loadUserProfile(auth) {
  if (!auth?.uid) return null;
  const user = await findUserById(auth.uid);
  if (!user) return null;

  const [bookmarkedEvents, recentQueriesList] = await Promise.all([
    listBookmarkIds(user.id),
    recentQueries(user.id, 5)
  ]);

  return {
    interests: Array.isArray(user.interests) ? user.interests : [],
    bookmarkedEvents,
    recentQueries: recentQueriesList
  };
}

/** Persists a query for later personalization. Never fails the request. */
export async function rememberSearch(auth, query) {
  if (!auth?.uid) return;
  try {
    await recordSearch(auth.uid, query);
  } catch (err) {
    console.warn('[ai] failed to record search history:', err.message);
  }
}

/** All events, unpaginated — shared by search and recommendations. */
export async function allEvents() {
  const { events } = await listEvents();
  return events;
}