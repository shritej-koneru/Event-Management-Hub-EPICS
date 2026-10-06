/**
 * Development users.
 *
 * `alice@uni.edu` exists so `/ai/recommendations` and `/ai/search` return
 * personalized results immediately after `npm run db:seed`. Passwords are
 * development-only placeholders and are hashed at seed time.
 *
 * SECURITY: this account uses a password published in this repository and must
 * never be created in production. Seeding skips it unless SEED_DEMO_USER is
 * explicitly enabled - see `resolveSeedDemoUser()` in src/config/index.js.
 * For production data, load the demo password from the environment instead:
 *
 *   SEED_DEMO_USER=true DEMO_USER_PASSWORD=<secret> npm run db:seed
 */

export const DEMO_USER = {
  id: 'usr_001',
  name: 'Alice Wang',
  email: 'alice@uni.edu',
  /** Falls back to the documented dev placeholder outside production. */
  password:
    (process.env.DEMO_USER_PASSWORD || '').trim() ||
    (process.env.NODE_ENV === 'production' ? '' : 'secret123'),
  interests: ['tech', 'ai', 'android']
};

/** Bookmarks + search history that give the demo user personalization signals. */
export const DEMO_PERSONALIZATION = {
  bookmarks: ['evt_001', 'evt_005'],
  searchHistory: ['android']
};

/**
 * Initial BitChat announcements.
 *
 * `eventId` ties each notice to the event it is about, so the Event Details
 * feed can show it. `room` remains the venue the message is broadcast into.
 */
export const SEED_ANNOUNCEMENTS = [
  {
    room: 'main-hall',
    message: 'Annual Tech Carnival opens tomorrow at Main Hall! 🎉',
    eventId: 'evt_003'
  },
  {
    room: 'room-204',
    message: 'Android Dev Workshop: bring a laptop, seats are filling up. 🤖',
    eventId: 'evt_001'
  }
];