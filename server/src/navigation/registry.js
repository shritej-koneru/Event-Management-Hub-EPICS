import { VENUES, GRAPH, MAP_POSITIONS } from './venues.js';

/**
 * LIVE VENUE REGISTRY — the in-memory copy of the DB-backed campus map.
 *
 * `venues`/`venue_edges` are the source of truth. This module caches them for
 * synchronous lookups (pathfinding, venue-name resolution, socket room
 * validation) and is rebuilt from the DB at boot and after every map write
 * (`refreshRegistry`). Until the first refresh it holds the static seed data
 * from ./venues.js, so unit code and tests that never touch the DB still work.
 *
 * Invariants checked on every rebuild (assertLiveGraph): edges are symmetric,
 * distances are positive, and every endpoint is a known venue. A venue with no
 * edges is allowed (it is simply unreachable -> NO_PATH).
 */

/** Reserved: stored `announcements.room` value for campus-wide announcements. */
export const GLOBAL_ANNOUNCE_ROOM = 'all';

let state = null;
let loadedFromDb = false;

function buildState(venueRows, edgeRows) {
  const byId = new Map();
  for (const row of venueRows) {
    byId.set(row.id, { id: row.id, name: row.name, x: Number(row.x), y: Number(row.y) });
  }

  // Stable order: canonical venues first (keeps turn-by-turn text unchanged),
  // then everything else by id.
  const canonical = VENUES.map((v) => v.id).filter((id) => byId.has(id));
  const canonicalSet = new Set(canonical);
  const extra = [...byId.keys()].filter((id) => !canonicalSet.has(id)).sort();
  const order = [...canonical, ...extra];

  const adjacency = Object.create(null);
  for (const id of order) adjacency[id] = Object.create(null);
  for (const edge of edgeRows) {
    if (!byId.has(edge.from) || !byId.has(edge.to)) {
      throw new Error(`Graph edge ${edge.from} -> ${edge.to} points at an unknown venue`);
    }
    adjacency[edge.from][edge.to] = edge.distanceM;
    adjacency[edge.to][edge.from] = edge.distanceM;
  }

  const nameToId = new Map();
  for (const v of byId.values()) nameToId.set(v.name, v.id);

  return { byId, order, adjacency, nameToId, edgeCount: edgeRows.length };
}

function staticState() {
  const edges = [];
  for (const [from, nbrs] of Object.entries(GRAPH)) {
    for (const [to, distanceM] of Object.entries(nbrs)) {
      if (from < to) edges.push({ from, to, distanceM });
    }
  }
  return buildState(
    VENUES.map((v) => ({ ...v, x: MAP_POSITIONS[v.id].x, y: MAP_POSITIONS[v.id].y })),
    edges
  );
}

state = staticState();

/** Structural checks on the live graph; throws on a corrupt graph. */
export function assertLiveGraph(s = state) {
  for (const [from, edges] of Object.entries(s.adjacency)) {
    if (!s.byId.has(from)) throw new Error(`Graph node "${from}" has no venue entry`);
    for (const [to, weight] of Object.entries(edges)) {
      if (!s.byId.has(to)) throw new Error(`Graph edge ${from} -> ${to} points at an unknown venue`);
      if (!Number.isFinite(weight) || weight <= 0) {
        throw new Error(`Graph edge ${from} -> ${to} has a non-positive distance`);
      }
      if (s.adjacency[to]?.[from] !== weight) {
        throw new Error(`Graph is not symmetric: ${from} -> ${to}`);
      }
    }
  }
}

/** Replaces the cached registry from `{id,name,x,y}[]` + `{from,to,distanceM}[]`. */
export function setRegistry(venueRows, edgeRows) {
  const next = buildState(venueRows, edgeRows);
  assertLiveGraph(next);
  state = next;
  loadedFromDb = true;
}

/** Reloads the cache from the database. Call after every map write. */
export async function refreshRegistry() {
  // Lazy import keeps this module free of a DB dependency for unit use.
  const { listVenueMapRows, listVenueEdgeRows } = await import('../models/venueModel.js');
  const [venueRows, edgeRows] = await Promise.all([listVenueMapRows(), listVenueEdgeRows()]);
  setRegistry(
    venueRows.map((r) => ({ id: r.id, name: r.name, x: r.map_x, y: r.map_y })),
    edgeRows.map((r) => ({ from: r.from_id, to: r.to_id, distanceM: r.distance_m }))
  );
}

/** Loads from the DB once (first request / boot); later calls are no-ops. */
export async function ensureRegistryLoaded() {
  if (!loadedFromDb) await refreshRegistry();
}

export function isRegistryLoaded() {
  return loadedFromDb;
}

/** Test helper: back to the static seed data. */
export function resetRegistryToStatic() {
  state = staticState();
  loadedFromDb = false;
}

export const venueIds = () => state.order.slice();
export const venues = () => state.order.map((id) => state.byId.get(id));
export const getVenue = (id) => state.byId.get(id) ?? null;
export const venueNameForId = (id) => state.byId.get(id)?.name ?? null;
export const adjacency = () => state.adjacency;
export const neighborsOf = (id) => Object.keys(state.adjacency[id] ?? {});
export const edgeCount = () => state.edgeCount;

export function isValidVenueId(id) {
  return typeof id === 'string' && state.byId.has(id);
}

/** Exact (case-sensitive) display-name match, whitespace-trimmed. */
export function venueIdForName(name) {
  if (typeof name !== 'string') return null;
  return state.nameToId.get(name.trim()) ?? null;
}

/** Accepts a node id or a display name; returns a node id or null (never guesses). */
export function resolveVenue(input) {
  if (typeof input !== 'string') return null;
  const trimmed = input.trim();
  if (!trimmed) return null;
  if (isValidVenueId(trimmed)) return trimmed;
  return venueIdForName(trimmed);
}

/** Connectivity summary from `entrance` (or the first venue). */
export function reachability() {
  const start = state.byId.has('entrance') ? 'entrance' : state.order[0];
  const reached = new Set(start ? [start] : []);
  const queue = start ? [start] : [];
  while (queue.length) {
    const cur = queue.shift();
    for (const next of Object.keys(state.adjacency[cur])) {
      if (!reached.has(next)) {
        reached.add(next);
        queue.push(next);
      }
    }
  }
  return {
    venueCount: state.order.length,
    reachableCount: reached.size,
    unreachable: state.order.filter((id) => !reached.has(id))
  };
}
