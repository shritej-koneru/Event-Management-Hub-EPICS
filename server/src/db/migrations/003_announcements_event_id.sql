-- ---------------------------------------------------------------------------
-- 003_announcements_event_id.sql
--
-- Associates announcements with the EVENT that produced them, not only with
-- the venue. This is required because two different events can be held at the
-- same venue (evt_002 Music Night and evt_006 Drama are both at "auditorium"),
-- and an event-scoped feed must never show one event's announcements under
-- another event that merely shares its room.
--
-- Design notes
-- ------------
-- * The column is ADDITIVE and NULLABLE. Existing rows keep working, and the
--   `announcements_room_message_key` UNIQUE (room, message) constraint is left
--   exactly as it was, so the Socket.IO relay's idempotency is unchanged.
--   The relay already embeds the event id in the message text
--   ("New event at Room 204 [evt_001]"), so (room, message) is already unique
--   per event in practice; the column adds queryability, not new semantics.
--
-- * NULL means "venue-level announcement that is not tied to a specific
--   event" - a room notice with no event behind it. Every announcement the
--   seeds and the relay write DOES carry an event id (SEED_ANNOUNCEMENTS supply
--   `eventId`, and the relay appends "[<eventId>]" to the message), so in
--   practice this is the rare case: a row inserted directly, or one written
--   before the relay learned to embed the id. Event-scoped queries filter on
--   `event_id = $1`, which deliberately EXCLUDES those rows rather than
--   guessing an association.
--
-- * ON DELETE CASCADE keeps announcements from outliving the event that
--   generated them.
-- ---------------------------------------------------------------------------

ALTER TABLE announcements
  ADD COLUMN IF NOT EXISTS event_id text REFERENCES events (id) ON UPDATE CASCADE ON DELETE CASCADE;

-- Backfill the rows the relay already wrote. The event id is the bracketed
-- suffix of the message; it is only applied when that event actually exists, so
-- the foreign key can never fail.
UPDATE announcements a
   SET event_id = substring(a.message FROM '\[(evt_[A-Za-z0-9_-]+)\]\s*$')
  WHERE a.event_id IS NULL
    AND a.message ~ '\[(evt_[A-Za-z0-9_-]+)\]\s*$'
    AND EXISTS (
      SELECT 1 FROM events e
       WHERE e.id = substring(a.message FROM '\[(evt_[A-Za-z0-9_-]+)\]\s*$')
    );

-- Serves GET /api/chat/announcements?eventId= and the Event Details feed.
-- Partial: venue-level rows (event_id IS NULL) are never part of this index.
CREATE INDEX IF NOT EXISTS idx_announcements_event
  ON announcements (event_id, id DESC)
  WHERE event_id IS NOT NULL;