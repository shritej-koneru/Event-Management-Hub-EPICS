import { describe, expect, it } from 'vitest';
import { api } from './helpers/testContext.js';
import { query } from '../src/db/pool.js';
import {
  VENUES,
  VENUE_IDS,
  GRAPH,
  MAP_POSITIONS,
  venueIdToName,
  resolveVenue
} from '../src/navigation/venues.js';
import { shortestPath, venueList, validateGraph } from '../src/navigation/graph.js';

describe('GET /api/navigation/venues', () => {
  it('returns all 14 canonical venues with neighbours', async () => {
    const response = await api().get('/api/navigation/venues');

    expect(response.status).toBe(200);
    expect(response.body.venues).toHaveLength(14);

    for (const venue of response.body.venues) {
      expect(typeof venue.id).toBe('string');
      expect(typeof venue.name).toBe('string');
      expect(Array.isArray(venue.neighbors)).toBe(true);
    }
  });

  it('exposes the canonical id/name pairs', async () => {
    const { body } = await api().get('/api/navigation/venues');
    const pairs = body.venues.map((v) => `${v.id}:${v.name}`);

    expect(pairs).toEqual(
      expect.arrayContaining([
        'entrance:Entrance',
        'lobby:Lobby',
        'registration:Registration Desk',
        'auditorium:Auditorium',
        'main-hall:Main Hall',
        'room-101:Room 101',
        'room-102:Room 102',
        'room-204:Room 204',
        'room-205:Room 205',
        'lab-a:Lab A',
        'lab-b:Lab B',
        'cafeteria:Cafeteria',
        'library:Library',
        'tech-lounge:Tech Lounge'
      ])
    );
  });
});

describe('POST /api/navigation/path', () => {
  it('resolves a path by graph node id', async () => {
    const response = await api()
      .post('/api/navigation/path')
      .send({ from: 'entrance', to: 'room-204' });

    expect(response.status).toBe(200);
    expect(response.body.nodes.length).toBeGreaterThanOrEqual(2);
    expect(response.body.nodes[0].id).toBe('entrance');
    expect(response.body.nodes.at(-1).id).toBe('room-204');
    expect(response.body.steps.length).toBe(response.body.nodes.length);
    expect(response.body.totalDistanceM).toBeGreaterThan(0);
  });

  it('resolves a path by canonical display name', async () => {
    const response = await api()
      .post('/api/navigation/path')
      .send({ from: 'Entrance', to: 'Auditorium' });

    expect(response.status).toBe(200);
    expect(response.body.nodes[0].name).toBe('Entrance');
    expect(response.body.nodes.at(-1).name).toBe('Auditorium');
  });

  it('returns a single-node path when from equals to', async () => {
    const response = await api()
      .post('/api/navigation/path')
      .send({ from: 'library', to: 'library' });

    expect(response.status).toBe(200);
    expect(response.body.nodes).toHaveLength(1);
    expect(response.body.totalDistanceM).toBe(0);
    expect(response.body.steps[0].instruction).toMatch(/Start at Library/);
  });

  it('returns 400 UNKNOWN_VENUE for an unknown destination', async () => {
    const response = await api()
      .post('/api/navigation/path')
      .send({ from: 'entrance', to: 'dormitory' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('does not silently coerce a near-miss venue name', async () => {
    const response = await api()
      .post('/api/navigation/path')
      .send({ from: 'entrance', to: 'room 204' });

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('returns 400 when from or to is missing', async () => {
    const response = await api().post('/api/navigation/path').send({ from: 'entrance' });
    expect(response.status).toBe(400);
    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('resolves a path between EVERY pair of venues (contract guarantee)', async () => {
    for (const from of VENUE_IDS) {
      for (const to of VENUE_IDS) {
        const response = await api().post('/api/navigation/path').send({ from, to });
        expect(response.status, `${from} -> ${to}`).toBe(200);
        expect(response.body.nodes.length).toBeGreaterThan(0);
        expect(response.body.nodes.at(-1).id).toBe(to);
        expect(response.body.totalDistanceM).toBeGreaterThanOrEqual(0);
      }
    }
  });
});

describe('GET /api/navigation/map', () => {
  it('returns every venue with an in-range fractional position', async () => {
    const response = await api().get('/api/navigation/map');

    expect(response.status).toBe(200);
    expect(response.body.venues).toHaveLength(14);

    for (const venue of response.body.venues) {
      expect(typeof venue.id).toBe('string');
      expect(typeof venue.name).toBe('string');
      expect(Number.isFinite(venue.x)).toBe(true);
      expect(Number.isFinite(venue.y)).toBe(true);
      expect(venue.x).toBeGreaterThanOrEqual(0);
      expect(venue.x).toBeLessThanOrEqual(1);
      expect(venue.y).toBeGreaterThanOrEqual(0);
      expect(venue.y).toBeLessThanOrEqual(1);
      expect(Array.isArray(venue.neighbors)).toBe(true);
    }
  });

  it('serves the canonical MAP_POSITIONS layout', async () => {
    const { body } = await api().get('/api/navigation/map');

    const byId = new Map(body.venues.map((v) => [v.id, v]));
    for (const { id } of VENUES) {
      expect(byId.has(id), `venue ${id} missing from map`).toBe(true);
      expect(byId.get(id).x).toBeCloseTo(MAP_POSITIONS[id].x, 5);
      expect(byId.get(id).y).toBeCloseTo(MAP_POSITIONS[id].y, 5);
    }
  });

  it('returns 19 undirected edges whose distances match GRAPH', async () => {
    const { body } = await api().get('/api/navigation/map');

    const expected = [];
    for (const [from, edges] of Object.entries(GRAPH)) {
      for (const [to, distance] of Object.entries(edges)) {
        if (from < to) expected.push({ from, to, distanceM: distance });
      }
    }
    expected.sort((a, b) => `${a.from}:${a.to}`.localeCompare(`${b.from}:${b.to}`));

    const actual = [...body.edges].sort((a, b) => `${a.from}:${a.to}`.localeCompare(`${b.from}:${b.to}`));
    expect(actual).toEqual(expected);
    expect(actual).toHaveLength(19);
  });

  it('keeps neighbour lists consistent with the edge list', async () => {
    const { body } = await api().get('/api/navigation/map');

    const venueIds = new Set(body.venues.map((v) => v.id));
    const edgeKeys = new Set(
      body.edges.flatMap((e) => [`${e.from}>${e.to}`, `${e.to}>${e.from}`])
    );

    for (const venue of body.venues) {
      expect(venue.neighbors.length).toBeGreaterThan(0);
      for (const neighbor of venue.neighbors) {
        expect(venueIds.has(neighbor), `${venue.id} -> unknown ${neighbor}`).toBe(true);
        expect(edgeKeys.has(`${venue.id}>${neighbor}`), `missing edge ${venue.id} -> ${neighbor}`).toBe(true);
      }
    }
  });
});

describe('GET /api/navigation/venues/:venueId/map', () => {
  it('returns one venue with positioned neighbours and distances', async () => {
    const response = await api().get('/api/navigation/venues/room-204/map');

    expect(response.status).toBe(200);
    expect(response.body.venue).toEqual({
      id: 'room-204',
      name: 'Room 204',
      x: MAP_POSITIONS['room-204'].x,
      y: MAP_POSITIONS['room-204'].y
    });

    const neighborIds = response.body.neighbors.map((n) => n.id);
    expect(neighborIds.sort()).toEqual(['lab-a', 'room-205', 'tech-lounge']);

    for (const neighbor of response.body.neighbors) {
      expect(typeof neighbor.name).toBe('string');
      expect(neighbor.x).toBeCloseTo(MAP_POSITIONS[neighbor.id].x, 5);
      expect(neighbor.y).toBeCloseTo(MAP_POSITIONS[neighbor.id].y, 5);
      expect(neighbor.distanceM).toBe(GRAPH['room-204'][neighbor.id]);
    }
  });

  it('accepts a URL-encoded canonical display name', async () => {
    const response = await api().get(`/api/navigation/venues/${encodeURIComponent('Room 204')}/map`);

    expect(response.status).toBe(200);
    expect(response.body.venue.id).toBe('room-204');
  });

  it('returns 400 UNKNOWN_VENUE for an unknown id', async () => {
    const response = await api().get('/api/navigation/venues/dormitory/map');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });

  it('rejects a near-miss name instead of guessing', async () => {
    const response = await api().get('/api/navigation/venues/room%20204/map');

    expect(response.status).toBe(400);
    expect(response.body.code).toBe('UNKNOWN_VENUE');
  });
});

describe('stored map data mirrors the canonical graph', () => {
  it('venue_edges stores exactly the undirected GRAPH edges', async () => {
    const { rows } = await query(
      'SELECT from_id, to_id, distance_m FROM venue_edges ORDER BY from_id, to_id'
    );

    const expected = [];
    for (const [from, edges] of Object.entries(GRAPH)) {
      for (const [to, distance] of Object.entries(edges)) {
        if (from < to) expected.push({ from_id: from, to_id: to, distance_m: distance });
      }
    }
    expected.sort((a, b) => `${a.from_id}:${a.to_id}`.localeCompare(`${b.from_id}:${b.to_id}`));

    expect(rows).toEqual(expected);
  });

  it('venues.map_x/map_y match MAP_POSITIONS for every node', async () => {
    const { rows } = await query('SELECT id, map_x, map_y FROM venues ORDER BY id');

    expect(rows).toHaveLength(VENUES.length);
    for (const row of rows) {
      expect(row.map_x).toBeCloseTo(MAP_POSITIONS[row.id].x, 5);
      expect(row.map_y).toBeCloseTo(MAP_POSITIONS[row.id].y, 5);
    }
  });
});

describe('navigation graph invariants', () => {
  it('passes structural validation', () => {
    const result = validateGraph();
    expect(result.venueCount).toBe(14);
    expect(result.reachableCount).toBe(14);
  });

  it('resolves display names and ids to the same node', () => {
    expect(resolveVenue('Room 204')).toBe('room-204');
    expect(resolveVenue('room-204')).toBe('room-204');
    expect(resolveVenue('  Main Hall  ')).toBe('main-hall');
    expect(resolveVenue('Room204')).toBeNull();
    expect(resolveVenue('')).toBeNull();
  });

  it('finds the true shortest path', () => {
    // entrance -> lobby -> main-hall -> room-102 -> tech-lounge -> room-204 = 115m
    expect(shortestPath('entrance', 'room-204').totalDistanceM).toBe(115);
    expect(shortestPath('entrance', 'lobby').totalDistanceM).toBe(40);
    expect(shortestPath('room-101', 'room-102').totalDistanceM).toBe(8);
  });

  it('exposes venueList() with neighbours for every venue', () => {
    const list = venueList();
    expect(list).toHaveLength(VENUES.length);
    expect(list.find((v) => v.id === 'room-204').neighbors).toEqual(
      expect.arrayContaining(['room-205', 'lab-a', 'tech-lounge'])
    );
  });

  it('keeps venueIdToName in sync with the venue list', () => {
    for (const venue of VENUES) {
      expect(venueIdToName.get(venue.id)).toBe(venue.name);
    }
  });
});