-- ---------------------------------------------------------------------------
-- 010_event_price_accessibility.sql  (additive; nothing is dropped or rewritten)
--
-- Explicit, typed price and accessibility information for events. "Unknown" is a first-class value and the default:
--   events.price_type          NULL = unknown | 'free' | 'paid'
--   events.price_amount_cents  only for 'paid' (> 0)
--   events.price_currency      only for 'paid' (ISO 4217 style, e.g. 'INR')
--   events.accessibility       jsonb object of boolean features. A key that is ABSENT is unknown, true means the
--                              feature is offered, false means it is explicitly not offered. Allowed keys are
--                              validated by the API (stepFree, accessibleRestroom, signLanguage, captions, hearingLoop).
--
-- Existing rows keep price_type NULL and accessibility '{}': nothing is inferred from descriptions or tags.
-- NOT applied to any live database by the code that adds this file: run `npm run db:migrate` on purpose.
-- ---------------------------------------------------------------------------
ALTER TABLE events ADD COLUMN IF NOT EXISTS price_type text;
ALTER TABLE events ADD COLUMN IF NOT EXISTS price_amount_cents integer;
ALTER TABLE events ADD COLUMN IF NOT EXISTS price_currency text;
ALTER TABLE events ADD COLUMN IF NOT EXISTS accessibility jsonb NOT NULL DEFAULT '{}'::jsonb;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'events_price_type_check') THEN
    ALTER TABLE events ADD CONSTRAINT events_price_type_check
      CHECK (price_type IS NULL OR price_type IN ('free', 'paid'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'events_price_consistent_check') THEN
    ALTER TABLE events ADD CONSTRAINT events_price_consistent_check CHECK (
      (price_type IS NULL AND price_amount_cents IS NULL AND price_currency IS NULL)
      OR (price_type = 'free' AND price_amount_cents IS NULL AND price_currency IS NULL)
      OR (price_type = 'paid' AND price_amount_cents > 0 AND price_currency IS NOT NULL)
    );
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'events_accessibility_object_check') THEN
    ALTER TABLE events ADD CONSTRAINT events_accessibility_object_check
      CHECK (jsonb_typeof(accessibility) = 'object');
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_events_price_type ON events (price_type);
