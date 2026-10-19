import { describeNodes, shortestPath } from '../navigation/graph.js';
import { resolveVenue } from '../navigation/venues.js';
import { listVenueMapRows, getVenueMapRow, listVenueEdgeRows } from '../models/venueModel.js';
import { ApiError } from '../utils/errors.js';

/**
 * Resolves a venue reference to a graph node id.
 * Input may be a node id ("room-204") or a canonical display name ("Room 204").
 * Nothing is guessed — an unmappable value raises UNKNOWN_VENUE.
 */
export function requireVenueNode(input, label = 'venue') {
  const venueId = resolveVenue(input);
  if (!venueId) {
    throw ApiError.badRequest(
      `Unknown venue: ${input}. Use a navigation graph node id or canonical display name.`,
      'UNKNOWN_VENUE'
    );
  }
  return venueId;
}

/**
 * POST /navigation/path
 *
 * @returns {{ nodes: {id,name}[], steps: object[], totalDistanceM: number }}
 */
export function findPath({ from, to }) {
  const fromId = requireVenueNode(from, 'from');
  const toId = requireVenueNode(to, 'to');
  const { nodeIds, steps, totalDistanceM } = shortestPath(fromId, toId);
  return { nodes: describeNodes(nodeIds), steps, totalDistanceM };
}

/**
 * Resolves an event venue into a walkable route from a start point. Used to
 * guarantee that every event venue is reachable in the graph.
 */
export function pathToEventVenue(from, venueId) {
  return findPath({ from, to: venueId });
}

/* -------------------------------------------------------------------------- */
/* Map (stored layout served from the database)                               */
/* -------------------------------------------------------------------------- */

function toMapNode(row) {
  return { id: row.id, name: row.name, x: Number(row.map_x), y: Number(row.map_y) };
}

/**
 * GET /navigation/map — the whole campus layout for the Android canvas:
 * every venue with its fractional position plus the undirected walk graph.
 * Everything comes from the seeded database (venues.map_x/map_y, venue_edges),
 * never from values hard-coded in the client.
 *
 * @returns {{ venues: {id,name,x,y,neighbors:string[]}[], edges: {from,to,distanceM}[] }}
 */
export async function campusMap() {
  const [venueRows, edgeRows] = await Promise.all([listVenueMapRows(), listVenueEdgeRows()]);

  const neighbors = new Map(venueRows.map((row) => [row.id, []]));
  for (const edge of edgeRows) {
    neighbors.get(edge.from_id)?.push(edge.to_id);
    neighbors.get(edge.to_id)?.push(edge.from_id);
  }

  return {
    venues: venueRows.map((row) => ({
      ...toMapNode(row),
      neighbors: neighbors.get(row.id) ?? []
    })),
    edges: edgeRows.map((edge) => ({
      from: edge.from_id,
      to: edge.to_id,
      distanceM: edge.distance_m
    }))
  };
}

/**
 * GET /navigation/venues/:venueId/map — one venue node with its positioned
 * neighbours and edge distances. `venueId` accepts a node id or a canonical
 * display name; anything unknown is a 400 UNKNOWN_VENUE (never a guess).
 *
 * @returns {{ venue: {id,name,x,y}, neighbors: {id,name,x,y,distanceM}[] }}
 */
export async function venueMap(venueRefInput) {
  const venueId = requireVenueNode(venueRefInput, 'venueId');
  const node = await getVenueMapRow(venueId);
  if (!node) {
    throw ApiError.badRequest(
      `Unknown venue: ${venueRefInput}. Use a navigation graph node id or canonical display name.`,
      'UNKNOWN_VENUE'
    );
  }

  const [neighborRows, edgeRows] = await Promise.all([listVenueMapRows(), listVenueEdgeRows()]);
  const positionById = new Map(neighborRows.map((row) => [row.id, row]));

  const neighbors = [];
  for (const edge of edgeRows) {
    let neighborId = null;
    if (edge.from_id === venueId) neighborId = edge.to_id;
    else if (edge.to_id === venueId) neighborId = edge.from_id;
    if (!neighborId) continue;

    const row = positionById.get(neighborId);
    if (!row) continue; // FK guarantees this never happens
    neighbors.push({ ...toMapNode(row), distanceM: edge.distance_m });
  }

  return { venue: toMapNode(node), neighbors };
}