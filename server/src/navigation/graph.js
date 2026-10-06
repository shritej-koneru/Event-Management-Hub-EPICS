import {
  GRAPH,
  VENUES,
  VENUE_IDS,
  assertAllVenuesReachable,
  assertGraphIntegrity,
  venueIdToName
} from './venues.js';
import { ApiError } from '../utils/errors.js';

const nodes = Object.keys(GRAPH);

/**
 * Dijkstra shortest path over the venue graph.
 * The graph has 14 nodes, so a linear scan for the next minimum is plenty
 * (no priority queue needed).
 *
 * @returns {{ nodeIds: string[], steps: object[], totalDistanceM: number }}
 * @throws {ApiError} 400 UNKNOWN_VENUE when a node id is not a graph node,
 *                    400 NO_PATH when the nodes are not connected.
 */
export function shortestPath(fromId, toId) {
  if (!Object.prototype.hasOwnProperty.call(GRAPH, fromId)) {
    throw ApiError.badRequest(`Unknown venue: ${fromId}`, 'UNKNOWN_VENUE');
  }
  if (!Object.prototype.hasOwnProperty.call(GRAPH, toId)) {
    throw ApiError.badRequest(`Unknown venue: ${toId}`, 'UNKNOWN_VENUE');
  }

  const dist = Object.create(null);
  const prev = Object.create(null);
  const settled = new Set();

  for (const id of nodes) {
    dist[id] = Infinity;
    prev[id] = null;
  }
  dist[fromId] = 0;

  while (settled.size < nodes.length) {
    let current = null;
    let best = Infinity;
    for (const id of nodes) {
      if (!settled.has(id) && dist[id] < best) {
        best = dist[id];
        current = id;
      }
    }
    if (current === null) break;
    settled.add(current);
    if (current === toId) break;

    for (const [neighbour, weight] of Object.entries(GRAPH[current])) {
      if (settled.has(neighbour)) continue;
      const candidate = dist[current] + weight;
      if (candidate < dist[neighbour]) {
        dist[neighbour] = candidate;
        prev[neighbour] = current;
      }
    }
  }

  if (!Number.isFinite(dist[toId])) {
    throw ApiError.badRequest(`No path between ${fromId} and ${toId}`, 'NO_PATH');
  }

  const nodeIds = [];
  for (let cursor = toId; cursor !== null; cursor = prev[cursor]) nodeIds.unshift(cursor);

  // Cumulative walking distance up to and including each node.
  const legLengths = new Map();
  for (let i = 1; i < nodeIds.length; i += 1) {
    legLengths.set(nodeIds[i], GRAPH[nodeIds[i - 1]][nodeIds[i]]);
  }

  let cumulative = 0;
  const steps = nodeIds.map((id, index) => {
    if (index > 0) cumulative += legLengths.get(id);
    return {
      node: id,
      name: venueIdToName.get(id),
      distanceM: index === 0 ? 0 : legLengths.get(id),
      instruction: instructionFor(nodeIds, index)
    };
  });

  return { nodeIds, steps, totalDistanceM: dist[toId] };
}

/** `{ id, name }` list for the `nodes` array in the path response. */
export function describeNodes(nodeIds) {
  return nodeIds.map((id) => ({ id, name: venueIdToName.get(id) }));
}

function instructionFor(nodeIds, index) {
  if (index === 0) return `Start at ${venueIdToName.get(nodeIds[0])}`;
  if (index === nodeIds.length - 1) return `Turn right, arrive at ${venueIdToName.get(nodeIds[index])}`;

  const previous = nodeIds[index - 1];
  const current = nodeIds[index];
  const next = nodeIds[index + 1];

  if (isStraight(previous, current, next)) return 'Go straight ahead';
  const delta = ((bearingFromTo(current, next) - bearingFromTo(previous, current)) % 4 + 4) % 4;
  const turn = { 1: 'Turn right', 2: 'Go back and head', 3: 'Turn left' }[delta] || 'Continue';
  return `${turn} toward ${venueIdToName.get(next)}`;
}

/**
 * Deterministic pseudo-compass so turn-by-turn text is stable across restarts.
 * Direction is derived from the venue ordering (0=N, 1=E, 2=S, 3=W).
 */
function bearingFromTo(fromId, toId) {
  const from = VENUE_IDS.indexOf(fromId);
  const to = VENUE_IDS.indexOf(toId);
  const dx = to - from;
  const dy = ((from * 7 + to * 3) % 5) - 2;
  if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? 1 : 3;
  return dy > 0 ? 2 : 0;
}

function isStraight(previous, current, next) {
  const a = VENUE_IDS.indexOf(previous);
  const b = VENUE_IDS.indexOf(current);
  const c = VENUE_IDS.indexOf(next);
  const first = b - a;
  const second = c - b;
  return first !== 0 && Math.sign(first) === Math.sign(second);
}

/** Full venue catalogue with neighbour ids, as returned by GET /navigation/venues. */
export function venueList() {
  return VENUES.map((venue) => ({
    id: venue.id,
    name: venue.name,
    neighbors: Object.keys(GRAPH[venue.id] || {})
  }));
}

/** Runs both structural checks; called at boot so a broken graph never ships. */
export function validateGraph() {
  assertGraphIntegrity();
  const reached = assertAllVenuesReachable();
  return { venueCount: VENUE_IDS.length, reachableCount: reached.size };
}