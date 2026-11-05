/** Pure helpers for the manager console (unit-tested in manage.test.ts). */
import { ApiError } from './api'
import type { EventItem, MapVenue } from './types'

// ---- venue slug ---------------------------------------------------------
export const SLUG_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/
export const SLUG_MAX = 40

export function isValidSlug(id: string): boolean {
  return id.length > 0 && id.length <= SLUG_MAX && SLUG_RE.test(id)
}

/** Suggest a slug from a display name ("Main Hall 2" -> "main-hall-2"). */
export function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, SLUG_MAX)
    .replace(/-+$/, '')
}

// ---- canvas <-> fraction coordinates -----------------------------------
export const CANVAS_W = 800
export const CANVAS_H = 600 // 4:3

export const clamp01 = (n: number): number => (Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0)

export function fractionToCanvas(f: number, size: number): number {
  return clamp01(f) * size
}

/** Canvas position -> fraction in [0,1], clamped. */
export function canvasToFraction(pos: number, size: number): number {
  if (size <= 0) return 0
  return clamp01(pos / size)
}

/** Round to 3 decimals so saved coordinates stay tidy. */
export const roundFraction = (n: number): number => Math.round(clamp01(n) * 1000) / 1000

// ---- distance prefill ---------------------------------------------------
export const DEFAULT_METRES_PER_UNIT = 200

/** Euclidean distance between two venues in canvas units (x scaled by the 4:3 aspect, so 1 unit = canvas height). */
export function euclideanUnits(a: Pick<MapVenue, 'x' | 'y'>, b: Pick<MapVenue, 'x' | 'y'>): number {
  const dx = (a.x - b.x) * (CANVAS_W / CANVAS_H)
  const dy = a.y - b.y
  return Math.hypot(dx, dy)
}

/** Suggested metres for a path, clamped to the server range 1..1000. */
export function prefillDistance(
  a: Pick<MapVenue, 'x' | 'y'>,
  b: Pick<MapVenue, 'x' | 'y'>,
  metresPerUnit = DEFAULT_METRES_PER_UNIT,
): number {
  const m = Math.round(euclideanUnits(a, b) * metresPerUnit)
  return Math.min(1000, Math.max(1, m))
}

// ---- event form -> payload ---------------------------------------------
export interface EventFormValues {
  title: string
  category: string
  venue: string
  date: string
  time: string
  durationMin: string
  capacity: string
  host: string
  description: string
}

export const emptyEventForm = (): EventFormValues => ({
  title: '',
  category: '',
  venue: '',
  date: '',
  time: '10:00',
  durationMin: '60',
  capacity: '50',
  host: '',
  description: '',
})

export function eventToForm(e: EventItem): EventFormValues {
  return {
    title: e.title,
    category: e.category,
    venue: e.venue,
    date: e.date,
    time: e.time,
    durationMin: String(e.durationMin),
    capacity: String(e.capacity),
    host: e.host,
    description: e.description,
  }
}

export function eventFormToPayload(f: EventFormValues) {
  return {
    title: f.title.trim(),
    category: f.category.trim(),
    venue: f.venue.trim(),
    date: f.date,
    time: f.time,
    durationMin: Number(f.durationMin),
    capacity: Number(f.capacity),
    host: f.host.trim(),
    description: f.description.trim(),
  }
}

/** Client-side check before sending; returns a message or null. */
export function validateEventForm(f: EventFormValues): string | null {
  if (!f.title.trim()) return 'Title is required.'
  if (!f.category.trim()) return 'Category is required.'
  if (!f.venue.trim()) return 'Pick a venue.'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f.date)) return 'Pick a date.'
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(f.time)) return 'Time must be HH:MM.'
  const cap = Number(f.capacity)
  if (!Number.isInteger(cap) || cap < 0) return 'Capacity must be a whole number, 0 or more.'
  const dur = Number(f.durationMin)
  if (!Number.isInteger(dur) || dur < 1 || dur > 1440) return 'Duration must be 1-1440 minutes.'
  return null
}

/** Upcoming = event start is now or later. */
/**
 * Same rule as the server's overview counts and PAST_DATE check: an event is
 * upcoming when its date is today or later, with "today" taken in UTC.
 * Keeps the dashboard numbers and the lists in agreement.
 */
export function isUpcoming(e: Pick<EventItem, 'date'>, now: Date = new Date()): boolean {
  return e.date >= now.toISOString().slice(0, 10)
}

// ---- API error -> message ----------------------------------------------
const CODE_MESSAGES: Record<string, string> = {
  PAST_DATE: 'The event date is in the past. Choose today or a later date.',
  DUPLICATE_EVENT: 'An event with this title, date and venue already exists.',
  DUPLICATE_VENUE: 'A venue with this id or name already exists.',
  DUPLICATE_EDGE: 'These two venues are already connected.',
  VENUE_IN_USE:
    'This venue is used by one or more events, so it cannot be deleted. Move or delete those events first.',
  UNKNOWN_VENUE: 'That venue does not exist (it may have been removed).',
  UNKNOWN_EDGE: 'That path no longer exists.',
  RATE_LIMITED: 'Too many announcements. Wait a minute and try again.',
  NETWORK_ERROR: 'Cannot reach the server. Check your connection.',
}

export function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (CODE_MESSAGES[err.code]) return CODE_MESSAGES[err.code]
    if (err.status === 429) return CODE_MESSAGES.RATE_LIMITED
    if (err.status === 403)
      return 'You do not have permission to do that. Organizers can only change their own events.'
    if (err.status === 404) return 'Not found. It may have been deleted.'
    return err.message
  }
  return err instanceof Error ? err.message : 'Something went wrong'
}
