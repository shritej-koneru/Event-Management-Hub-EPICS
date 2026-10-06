-- ---------------------------------------------------------------------------
-- 002_integrity_and_retention.sql
--
-- Closes the two integrity findings left over from the initial schema:
--
--  1. announcements.room stored an unconstrained free-text venue id, so the
--     relay could persist a message for a venue that does not exist in the
--     navigation graph. `room` always holds a bare venue id, so it is a real
--     foreign key to venues(id).
--
--  2. search_history grew without bound and its index did not match the
--     `DISTINCT ON (lower(query)) ... ORDER BY lower(query), at DESC` query
--     that actually reads it.
-- ---------------------------------------------------------------------------

-- 1. announcements.room -> venues(id)
--
-- Orphans are removed first so the FK can be added in the same transaction:
-- an announcement for a venue that no longer exists is unreachable from the
-- BitChat map UI (which only ever renders graph venues), so dropping it loses
-- nothing that was ever displayable.
DELETE FROM announcements a
      WHERE NOT EXISTS (SELECT 1 FROM venues v WHERE v.id = a.room);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'announcements_room_fkey'
  ) THEN
    ALTER TABLE announcements
      ADD CONSTRAINT announcements_room_fkey
      FOREIGN KEY (room) REFERENCES venues (id) ON UPDATE CASCADE ON DELETE CASCADE;
  END IF;
END
$$;

-- 2a. Index that matches the DISTINCT ON read in recentQueries().
--
-- Ordering by lower(query) first lets Postgres walk distinct values in index
-- order and stop after `limit`, instead of sorting the user's whole history.
CREATE INDEX IF NOT EXISTS idx_search_history_user_query
  ON search_history (user_id, lower(query), at DESC);

-- 2b. Retention.
--
-- Only the 5 most recent distinct queries per user are ever read, so anything
-- beyond the newest 50 rows for that user is unreachable. Pruning runs on
-- write, which keeps the table bounded without a cron job, and is scoped per
-- user so it never scans more than one user's history.
CREATE OR REPLACE FUNCTION prune_search_history(max_per_user integer DEFAULT 50)
RETURNS integer
LANGUAGE sql
AS $$
  WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY user_id ORDER BY at DESC) AS rank
      FROM search_history
  ), deleted AS (
    DELETE FROM search_history s
     USING ranked
     WHERE s.id = ranked.id
       AND ranked.rank > max_per_user
    RETURNING 1
  )
  SELECT COUNT(*)::integer FROM deleted;
$$;

COMMENT ON FUNCTION prune_search_history(integer) IS
  'Deletes search_history rows beyond the newest max_per_user rows. Called on write.';