/** Display formats from the design system: `Thu 6 Nov` and `6 Nov, 14:30` (24 h). */

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

const pad = (n: number) => String(n).padStart(2, '0')

/** Parse `YYYY-MM-DD` as a calendar date (no timezone shift). */
function parseDay(date: string): { y: number; m: number; d: number } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date)
  if (!match) return null
  const y = Number(match[1])
  const m = Number(match[2])
  const d = Number(match[3])
  if (m < 1 || m > 12 || d < 1 || d > 31) return null
  return { y, m, d }
}

/** `2026-11-06` -> `Fri 6 Nov`. Returns the input unchanged when it is not a date. */
export function formatDate(date: string): string {
  const p = parseDay(date)
  if (!p) return date
  const dow = new Date(Date.UTC(p.y, p.m - 1, p.d)).getUTCDay()
  return `${DAYS[dow]} ${p.d} ${MONTHS[p.m - 1]}`
}

/** ISO timestamp (or Date) -> `6 Nov, 14:30` in the viewer's local time. Empty string if invalid. */
export function formatDateTime(value: string | Date): string {
  const t = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(t.getTime())) return ''
  return `${t.getDate()} ${MONTHS[t.getMonth()]}, ${pad(t.getHours())}:${pad(t.getMinutes())}`
}

/** ISO timestamp -> `14:30` local, or empty string. */
export function formatTime(value: string | Date): string {
  const t = value instanceof Date ? value : new Date(value)
  return Number.isNaN(t.getTime()) ? '' : `${pad(t.getHours())}:${pad(t.getMinutes())}`
}

/** Category -> token class for its dot (the text label always stays next to it). */
export function categoryDotClass(category: string): string {
  const c = category.toLowerCase()
  if (/tech|science|engineer|cod/.test(c)) return 'bg-cat-tech'
  if (/cultur|art|music|dance|film/.test(c)) return 'bg-cat-arts'
  if (/entertain|fun|game|social/.test(c)) return 'bg-cat-entertainment'
  if (/well|health|yoga|mind/.test(c)) return 'bg-cat-wellness'
  if (/career|job|intern|placement|workshop/.test(c)) return 'bg-cat-career'
  if (/sport|athlet|fitness/.test(c)) return 'bg-cat-sports'
  return 'bg-cat-other'
}

/** Strip the internal dedupe suffix the server appends to automatic announcements, e.g. "... [evt_mw41ab]". */
export function cleanAnnouncement(message: string): string {
  return message.replace(/\s*\[evt_[^\]]*\]\s*$/i, '').trim()
}

/** Audience label for an announcement: "All attendees" for global/"all"/empty, else the venue name (id as fallback). */
export function roomLabel(
  room: string | null | undefined,
  names: Record<string, string>,
  global?: boolean,
): string {
  if (global || !room || room.toLowerCase() === 'all') return 'All attendees'
  return names[room] ?? room
}
