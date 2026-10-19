-- ---------------------------------------------------------------------------
-- 005_venue_map.sql
--
-- Moves the campus map layout out of the Android client and into the
-- database, so GET /api/navigation/map can serve the drawing data:
--
--  1. venues.map_x / venues.map_y — fractional canvas coordinates (0..1)
--     seeded from the canonical MAP_POSITIONS in src/navigation/venues.js.
--     The Android MapScreen previously hard-coded these as `MapPositions`.
--
--  2. venue_edges — the navigation graph as stored rows (the JS GRAPH stays
--     the canonical definition and is mirrored here by the seed, exactly
--     like venues itself). Pathfinding keeps using the in-memory graph
--     (validated at boot); the API serves the stored copy.
-- ---------------------------------------------------------------------------

-- 1. Map coordinates.
--
-- Existing rows (a seeded dev database) fall back to the canvas centre; the
-- seed immediately overwrites them with the canonical layout.
ALTER TABLE venues ADD COLUMN IF NOT EXISTS map_x double precision NOT NULL DEFAULT 0.5;
ALTER TABLE venues ADD COLUMN IF NOT EXISTS map_y double precision NOT NULL DEFAULT 0.5;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'venues_map_x_range'
  ) THEN
    ALTER TABLE venues
      ADD CONSTRAINT venues_map_x_range CHECK (map_x >= 0 AND map_x <= 1);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'venues_map_y_range'
  ) THEN
    ALTER TABLE venues
      ADD CONSTRAINT venues_map_y_range CHECK (map_y >= 0 AND map_y <= 1);
  END IF;
END
$$;

-- 2. Stored navigation edges.
--
-- One row per UNDIRECTED edge (CHECK from_id < to_id), so the symmetric JS
-- graph is stored once and the API can draw each line a single time.
CREATE TABLE IF NOT EXISTS venue_edges (
  from_id    text    NOT NULL REFERENCES venues (id) ON UPDATE CASCADE ON DELETE CASCADE,
  to_id      text    NOT NULL REFERENCES venues (id) ON UPDATE CASCADE ON DELETE CASCADE,
  distance_m integer NOT NULL CHECK (distance_m > 0),
  PRIMARY KEY (from_id, to_id),
  CHECK (from_id < to_id)
);

COMMENT ON TABLE venue_edges IS
  'Navigation graph edges mirrored from GRAPH in src/navigation/venues.js (one row per undirected edge).';
