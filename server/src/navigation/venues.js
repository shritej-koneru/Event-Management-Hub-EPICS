/**
 * CANONICAL VENUE LIST — single source of truth.
 *
 * Mirrors `contract/API-CONTRACT.md` §1 and the Android map layout
 * (`android/.../ui/screens/MapScreen.kt` → `MapPositions`). Every event's
 * `venue` string and every navigation graph node id is keyed on these values.
 *
 * Changing a name here REQUIRES updating the contract doc and the Android map
 * layout together, otherwise routing breaks (this is the project's #1
 * integration risk). See server/README.md § "Venue naming convention".
 */

export const VENUES = [
  { id: 'entrance',      name: 'Entrance' },
  { id: 'lobby',         name: 'Lobby' },
  { id: 'registration',  name: 'Registration Desk' },
  { id: 'auditorium',    name: 'Auditorium' },
  { id: 'main-hall',     name: 'Main Hall' },
  { id: 'room-101',      name: 'Room 101' },
  { id: 'room-102',      name: 'Room 102' },
  { id: 'room-204',      name: 'Room 204' },
  { id: 'room-205',      name: 'Room 205' },
  { id: 'lab-a',         name: 'Lab A' },
  { id: 'lab-b',         name: 'Lab B' },
  { id: 'cafeteria',     name: 'Cafeteria' },
  { id: 'library',       name: 'Library' },
  { id: 'tech-lounge',   name: 'Tech Lounge' }
];

/**
 * Undirected adjacency list with physical walking distances in metres.
 * Must be symmetric — `assertGraphIntegrity()` enforces it.
 */
export const GRAPH = {
  'entrance':      { 'lobby': 40 },
  'lobby':         { 'entrance': 40, 'registration': 15, 'main-hall': 30 },
  'registration':  { 'lobby': 15, 'auditorium': 20 },
  'auditorium':    { 'registration': 20, 'main-hall': 25, 'tech-lounge': 30 },
  'main-hall':     { 'lobby': 30, 'auditorium': 25, 'room-101': 18, 'room-102': 18, 'cafeteria': 22 },
  'room-101':      { 'main-hall': 18, 'room-102': 8 },
  'room-102':      { 'main-hall': 18, 'room-101': 8, 'tech-lounge': 12 },
  'room-204':      { 'room-205': 8, 'lab-a': 10, 'tech-lounge': 15 },
  'room-205':      { 'room-204': 8, 'lab-b': 10 },
  'lab-a':         { 'room-204': 10, 'lab-b': 8, 'library': 20 },
  'lab-b':         { 'lab-a': 8, 'room-205': 10 },
  'cafeteria':     { 'main-hall': 22, 'tech-lounge': 18, 'library': 25 },
  'library':       { 'lab-a': 20, 'cafeteria': 25 },
  'tech-lounge':   { 'auditorium': 30, 'room-102': 12, 'room-204': 15, 'cafeteria': 18 }
};

/** Coarse spatial ordering used to derive synthetic compass bearings for turn-by-turn text. */
const ORDER = VENUES.map((v) => v.id);

export const VENUE_IDS = Object.freeze(ORDER.slice());

export const venueIdToName = new Map(VENUES.map((v) => [v.id, v.name]));

/**
 * Display names are matched EXACTLY (case-sensitive), as required by
 * contract/API-CONTRACT.md §1. Surrounding whitespace is tolerated because it
 * is never a spelling difference; casing and internal spacing are not, so
 * "room 204" or "Room204" fail loudly instead of resolving to a guess.
 */
export const venueNameToId = new Map(VENUES.map((v) => [v.name, v.id]));

export function isValidVenueId(id) {
  return typeof id === 'string' && Object.prototype.hasOwnProperty.call(GRAPH, id);
}

/**
 * Resolves a canonical display name ("Room 204") to its node id ("room-204").
 * Never guesses: an unrecognised name returns null so callers can raise
 * UNKNOWN_VENUE rather than produce an invalid route.
 */
export function venueIdForName(name) {
  if (typeof name !== 'string') return null;
  return venueNameToId.get(name.trim()) ?? null;
}

export function venueNameForId(id) {
  return venueIdToName.get(id) ?? null;
}

/**
 * Accepts either a node id or a display name and returns a node id.
 * Used by POST /navigation/path and by event creation.
 */
export function resolveVenue(input) {
  if (typeof input !== 'string') return null;
  const trimmed = input.trim();
  if (!trimmed) return null;
  if (isValidVenueId(trimmed)) return trimmed;
  return venueIdForName(trimmed);
}

export function assertGraphIntegrity() {
  for (const [from, edges] of Object.entries(GRAPH)) {
    if (!venueIdToName.has(from)) {
      throw new Error(`Graph node "${from}" has no canonical venue entry`);
    }
    for (const [to, weight] of Object.entries(edges)) {
      if (!venueIdToName.has(to)) {
        throw new Error(`Graph edge ${from} -> ${to} points at an unknown venue`);
      }
      if (!Number.isFinite(weight) || weight <= 0) {
        throw new Error(`Graph edge ${from} -> ${to} has a non-positive distance`);
      }
      if (GRAPH[to]?.[from] !== weight) {
        throw new Error(`Graph is not symmetric: ${from} -> ${to} = ${weight} but ${to} -> ${from} = ${GRAPH[to]?.[from]}`);
      }
    }
  }
}

/** Every venue must be walkable from `entrance`, otherwise routing can fail at runtime. */
export function assertAllVenuesReachable() {
  const reached = new Set(['entrance']);
  const queue = ['entrance'];
  while (queue.length) {
    const current = queue.shift();
    for (const next of Object.keys(GRAPH[current] || {})) {
      if (!reached.has(next)) {
        reached.add(next);
        queue.push(next);
      }
    }
  }
  const unreachable = VENUE_IDS.filter((id) => !reached.has(id));
  if (unreachable.length) {
    throw new Error(`Navigation graph unreachable nodes: ${unreachable.join(', ')}`);
  }
  return reached;
}