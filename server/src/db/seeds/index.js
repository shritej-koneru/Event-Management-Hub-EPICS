import bcrypt from 'bcryptjs';
import { pathToFileURL } from 'node:url';
import { config } from '../../config/index.js';
import { getPool } from '../pool.js';
import { ensureDatabase, releaseDatabase } from '../bootstrap.js';
import { validateGraph } from '../../navigation/graph.js';
import { isValidVenueId, assertMapIntegrity } from '../../navigation/venues.js';
import { upsertVenues, upsertVenueEdges, countVenues } from '../../models/venueModel.js';
import { insertEvent, countEvents } from '../../models/eventModel.js';
import { findUserByEmail, insertUser, countUsers } from '../../models/userModel.js';
import { addBookmark, listBookmarkIds } from '../../models/bookmarkModel.js';
import { recentQueries, recordSearch } from '../../models/searchHistoryModel.js';
import { recordAnnouncement, countAnnouncements } from '../../models/announcementModel.js';
import { SEED_EVENTS } from './events.js';
import { DEMO_USER, DEMO_PERSONALIZATION, SEED_ANNOUNCEMENTS } from './users.js';

/**
 * Idempotent seed. Safe to run on every boot — nothing is ever duplicated.
 *
 * Order matters: venues before events (FK), users before bookmarks.
 *
 * @param {object}  [options]
 * @param {boolean} [options.demoUser] Overrides `config.seedDemoUser`. The demo
 *   account has a publicly documented password, so it is skipped by default in
 *   production. When skipped, its bookmarks and search history are skipped too.
 * @returns {Promise<{venues:number, venueEdges:number, events:number, users:number, announcements:number, demoUser:boolean}>}
 */
export async function seed({ log = console.log, demoUser = config.seedDemoUser } = {}) {
  // Fail fast if the graph or the seed data drifted apart.
  const graph = validateGraph();
  log(`  navigation graph: ${graph.reachableCount}/${graph.venueCount} venues reachable from Entrance`);

  // The stored map layout must cover every venue and stay inside the canvas.
  assertMapIntegrity();

  for (const event of SEED_EVENTS) {
    if (!isValidVenueId(event.venueId)) {
      throw new Error(
        `Seed event ${event.id} references "${event.venueId}", which is not a navigation graph node`
      );
    }
  }

  const venueCount = await upsertVenues();
  log(`  venues: ${venueCount} synced`);

  // Mirror GRAPH into venue_edges so the map API serves the stored copy.
  const edgeCount = await upsertVenueEdges();
  log(`  venue edges: ${edgeCount} synced`);

  let eventsInserted = 0;
  for (const event of SEED_EVENTS) {
    const existing = await getPool().query('SELECT 1 FROM events WHERE id = $1', [event.id]);
    if (existing.rowCount > 0) continue;
    await insertEvent({ ...event, registered: Math.min(event.registered, event.capacity) });
    eventsInserted += 1;
  }
  log(`  events: ${eventsInserted} inserted (${SEED_EVENTS.length} defined)`);

  if (demoUser) {
    if (!DEMO_USER.password) {
      throw new Error(
        'SEED_DEMO_USER is enabled but no demo password is available. ' +
          'Set DEMO_USER_PASSWORD to a secret value.'
      );
    }

    // Demo user, created once. The password is intentionally never logged.
    if (!(await findUserByEmail(DEMO_USER.email))) {
      await insertUser({
        id: DEMO_USER.id,
        name: DEMO_USER.name,
        email: DEMO_USER.email,
        passwordHash: await bcrypt.hash(DEMO_USER.password, config.bcryptRounds),
        interests: DEMO_USER.interests
      });
      log(`  demo user: ${DEMO_USER.email} created`);
    }

    for (const eventId of DEMO_PERSONALIZATION.bookmarks) {
      await addBookmark(DEMO_USER.id, eventId);
    }

    const alreadySearched = await recentQueries(DEMO_USER.id, 20);
    for (const query of DEMO_PERSONALIZATION.searchHistory) {
      if (!alreadySearched.includes(query)) await recordSearch(DEMO_USER.id, query);
    }
  } else {
    log('  demo user: skipped (SEED_DEMO_USER is disabled)');
  }

  // ON CONFLICT DO NOTHING keeps repeated seeding from duplicating messages.
  let announcementsCreated = 0;
  for (const announcement of SEED_ANNOUNCEMENTS) {
    const { created } = await recordAnnouncement(
      announcement.room,
      announcement.message,
      announcement.eventId ?? null
    );
    if (created) announcementsCreated += 1;
  }
  log(`  announcements: ${announcementsCreated} created`);

  const counts = {
    venues: await countVenues(),
    venueEdges: edgeCount,
    events: await countEvents(),
    users: await countUsers(),
    bookmarks: demoUser ? (await listBookmarkIds(DEMO_USER.id)).length : 0,
    announcements: await countAnnouncements(),
    demoUser
  };
  log(`  db ready: ${JSON.stringify(counts)}`);

  return counts;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  await ensureDatabase();
  try {
    await seed();
  } catch (err) {
    console.error('[seed] failed:', err.message);
    process.exitCode = 1;
  } finally {
    await releaseDatabase();
  }
}