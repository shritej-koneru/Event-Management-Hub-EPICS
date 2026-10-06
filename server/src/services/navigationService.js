import { describeNodes, shortestPath } from '../navigation/graph.js';
import { resolveVenue } from '../navigation/venues.js';
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