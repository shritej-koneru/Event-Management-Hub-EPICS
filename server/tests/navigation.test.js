import { describe, expect, it } from 'vitest';
import { api } from './helpers/testContext.js';
import { VENUES, VENUE_IDS, venueIdToName, resolveVenue } from '../src/navigation/venues.js';
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