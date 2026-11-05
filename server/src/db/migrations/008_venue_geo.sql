-- ---------------------------------------------------------------------------
-- 008_venue_geo.sql  (additive, contract 1.5.0)
--
--  1. venues: lat / lng (nullable, set only when a manager placed the venue on
--     the real map), kind + description (free text), geo_placed flag. Seeded
--     venues keep lat/lng NULL; the API derives them from map_x/map_y using
--     the campus bounds.
--  2. announcements: manager announcements (sent_by NOT NULL) may repeat the
--     same text to the same room. The blanket UNIQUE (room, message)
--     constraint is replaced by a partial unique index that only covers
--     automatic rows (sent_by IS NULL: event relays + seed), which keeps the
--     idempotent event relay intact.
-- ---------------------------------------------------------------------------
ALTER TABLE venues ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE venues ADD COLUMN IF NOT EXISTS lng double precision;
ALTER TABLE venues ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT '';
ALTER TABLE venues ADD COLUMN IF NOT EXISTS description text NOT NULL DEFAULT '';
ALTER TABLE venues ADD COLUMN IF NOT EXISTS geo_placed boolean NOT NULL DEFAULT false;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'venues_description_len') THEN
    ALTER TABLE venues ADD CONSTRAINT venues_description_len CHECK (char_length(description) <= 500);
  END IF;
END
$$;

ALTER TABLE announcements DROP CONSTRAINT IF EXISTS announcements_room_message_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_announcements_auto_room_message
  ON announcements (room, message)
  WHERE sent_by IS NULL;
