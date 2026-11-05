/**
 * Campus geo helpers. Pure (no I/O, no config) so they can be unit tested and
 * shared by the map API, the manager service and the registry.
 *
 * The 4:3 graph canvas (x right, y down, both 0..1) is a linear projection of
 * the CAMPUS_BOUNDS lat/lng rectangle. At ~500 m the linear error is
 * negligible. This is the single source of truth: the server returns `bounds`
 * from GET /navigation/map and clients must use that, never their own copy.
 *
 * Campus = VR Siddhartha Engineering College (centre matches the Android
 * app's NavigationUiLogic.COLLEGE_LAT/LNG).
 */
export const CAMPUS_BOUNDS = Object.freeze({
  north: 16.487077,
  south: 16.483477,
  west: 80.69028,
  east: 80.69528
});

const EARTH_RADIUS_M = 6_371_000;
const toRad = (deg) => (deg * Math.PI) / 180;

/** Public `bounds` object served to clients (with computed centre). */
export function boundsPayload(bounds = CAMPUS_BOUNDS) {
  return {
    north: bounds.north,
    south: bounds.south,
    west: bounds.west,
    east: bounds.east,
    center: {
      lat: (bounds.north + bounds.south) / 2,
      lng: (bounds.west + bounds.east) / 2
    }
  };
}

/** True when lat/lng are finite and inside the (inclusive) bounds. */
export function isInsideBounds(lat, lng, bounds = CAMPUS_BOUNDS) {
  return (
    Number.isFinite(lat) &&
    Number.isFinite(lng) &&
    lat <= bounds.north &&
    lat >= bounds.south &&
    lng >= bounds.west &&
    lng <= bounds.east
  );
}

/** lat/lng -> canvas {x, y} in 0..1 (not clamped; check isInsideBounds first). */
export function latLngToXY(lat, lng, bounds = CAMPUS_BOUNDS) {
  return {
    x: (lng - bounds.west) / (bounds.east - bounds.west),
    y: (bounds.north - lat) / (bounds.north - bounds.south)
  };
}

/** Canvas {x, y} -> lat/lng (inverse of latLngToXY). */
export function xyToLatLng(x, y, bounds = CAMPUS_BOUNDS) {
  return {
    lat: bounds.north - y * (bounds.north - bounds.south),
    lng: bounds.west + x * (bounds.east - bounds.west)
  };
}

/** Great-circle distance in metres between two lat/lng points. */
export function haversineMeters(lat1, lng1, lat2, lng2) {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * Effective lat/lng of a venue: the stored pair when present, otherwise
 * derived from x/y via the inverse projection.
 * @param {{x:number,y:number,lat?:number|null,lng?:number|null}} venue
 */
export function effectiveLatLng(venue) {
  if (Number.isFinite(venue.lat) && Number.isFinite(venue.lng)) {
    return { lat: venue.lat, lng: venue.lng };
  }
  return xyToLatLng(venue.x, venue.y);
}

/**
 * Nearest venue to a point by haversine on effective lat/lng.
 * @returns {{ venue: object, distanceM: number }|null} null when `venues` is empty
 */
export function nearestVenue(lat, lng, venues) {
  let best = null;
  for (const venue of venues) {
    const p = effectiveLatLng(venue);
    const d = haversineMeters(lat, lng, p.lat, p.lng);
    if (!best || d < best.distanceM) best = { venue, distanceM: d };
  }
  return best;
}

/**
 * The additive 1.5.0 per-venue fields (lat/lng always present: stored or
 * derived from x/y). Accepts a registry venue or a DB-shaped venue.
 */
export function geoFields(venue) {
  const { lat, lng } = effectiveLatLng(venue);
  return {
    lat,
    lng,
    kind: venue.kind ?? '',
    description: venue.description ?? '',
    geoPlaced: Boolean(venue.geoPlaced)
  };
}
