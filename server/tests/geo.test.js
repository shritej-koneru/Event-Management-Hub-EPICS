import { describe, expect, it } from 'vitest';
import {
  CAMPUS_BOUNDS,
  boundsPayload,
  effectiveLatLng,
  geoFields,
  haversineMeters,
  isInsideBounds,
  latLngToXY,
  nearestVenue,
  xyToLatLng
} from '../src/navigation/geo.js';

describe('campus projection', () => {
  it('maps the corners and centre onto the 4:3 canvas', () => {
    const { north, south, west, east } = CAMPUS_BOUNDS;
    expect(latLngToXY(north, west)).toEqual({ x: 0, y: 0 });
    const se = latLngToXY(south, east);
    expect(se.x).toBeCloseTo(1, 12);
    expect(se.y).toBeCloseTo(1, 12);
    const c = latLngToXY((north + south) / 2, (west + east) / 2);
    expect(c.x).toBeCloseTo(0.5, 12);
    expect(c.y).toBeCloseTo(0.5, 12);
  });

  it('round-trips lat/lng -> x/y -> lat/lng and x/y -> lat/lng -> x/y', () => {
    for (const [lat, lng] of [
      [16.4859, 80.6915],
      [16.4841, 80.6941],
      [16.48705, 80.69029]
    ]) {
      const { x, y } = latLngToXY(lat, lng);
      const back = xyToLatLng(x, y);
      expect(back.lat).toBeCloseTo(lat, 9);
      expect(back.lng).toBeCloseTo(lng, 9);
    }
    for (const [x, y] of [
      [0, 0],
      [0.25, 0.75],
      [1, 1]
    ]) {
      const { lat, lng } = xyToLatLng(x, y);
      const back = latLngToXY(lat, lng);
      expect(back.x).toBeCloseTo(x, 9);
      expect(back.y).toBeCloseTo(y, 9);
    }
  });

  it('checks bounds inclusively and rejects non-finite values', () => {
    const { north, south, west, east } = CAMPUS_BOUNDS;
    expect(isInsideBounds(north, west)).toBe(true);
    expect(isInsideBounds(south, east)).toBe(true);
    expect(isInsideBounds(16.4853, 80.6928)).toBe(true);
    expect(isInsideBounds(north + 0.0001, 80.6928)).toBe(false);
    expect(isInsideBounds(16.4853, east + 0.0001)).toBe(false);
    expect(isInsideBounds(south - 0.0001, 80.6928)).toBe(false);
    expect(isInsideBounds(16.4853, west - 0.0001)).toBe(false);
    expect(isInsideBounds(NaN, 80.6928)).toBe(false);
    expect(isInsideBounds(16.4853, Infinity)).toBe(false);
  });

  it('serves bounds with the campus centre', () => {
    const b = boundsPayload();
    expect(b).toMatchObject({ north: 16.487077, south: 16.483477, west: 80.69028, east: 80.69528 });
    expect(b.center.lat).toBeCloseTo(16.485277, 9);
    expect(b.center.lng).toBeCloseTo(80.69278, 9);
  });
});

describe('haversine + nearest', () => {
  it('is 0 for identical points and about 111 km per degree of latitude', () => {
    expect(haversineMeters(16.485, 80.69, 16.485, 80.69)).toBe(0);
    expect(haversineMeters(16, 80, 17, 80)).toBeGreaterThan(111_000);
    expect(haversineMeters(16, 80, 17, 80)).toBeLessThan(111_400);
  });

  it('the campus is roughly 533 m wide and 400 m tall', () => {
    const { north, south, west, east } = CAMPUS_BOUNDS;
    const mid = (north + south) / 2;
    expect(haversineMeters(mid, west, mid, east)).toBeGreaterThan(520);
    expect(haversineMeters(mid, west, mid, east)).toBeLessThan(545);
    expect(haversineMeters(north, west, south, west)).toBeGreaterThan(395);
    expect(haversineMeters(north, west, south, west)).toBeLessThan(405);
  });

  it('prefers stored lat/lng and falls back to x/y for effective position', () => {
    expect(effectiveLatLng({ x: 0, y: 0, lat: 16.486, lng: 80.691 })).toEqual({ lat: 16.486, lng: 80.691 });
    const derived = effectiveLatLng({ x: 0, y: 0, lat: null, lng: null });
    expect(derived).toEqual({ lat: CAMPUS_BOUNDS.north, lng: CAMPUS_BOUNDS.west });
    expect(geoFields({ x: 0, y: 0 })).toMatchObject({ kind: '', description: '', geoPlaced: false });
  });

  it('picks the nearest venue and null for an empty list', () => {
    const list = [
      { id: 'a', x: 0, y: 0 },
      { id: 'b', x: 1, y: 1 },
      { id: 'c', x: 0.5, y: 0.5, lat: 16.4851, lng: 80.6926 }
    ];
    const near = nearestVenue(16.48505, 80.69255, list);
    expect(near.venue.id).toBe('c');
    expect(near.distanceM).toBeGreaterThan(0);
    expect(nearestVenue(16.4853, 80.6928, [])).toBeNull();
  });
});
