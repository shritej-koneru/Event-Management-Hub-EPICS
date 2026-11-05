import { describe, expect, it } from 'vitest'

import {
  buildSearch,
  countUnread,
  filterEvents,
  isUnread,
  mergeMessages,
  parseSelection,
  relativeTime,
  sortMessages,
  sortTickets,
  totalOpen,
} from './support'
import type { SupportMessage, SupportTicket } from './types'

const msg = (id: number, at: string, extra: Partial<SupportMessage> = {}): SupportMessage => ({
  id,
  ticketId: 1,
  senderId: 'u',
  senderName: 'U',
  senderRole: 'user',
  body: 'hi',
  createdAt: at,
  ...extra,
})

const ticket = (id: number, last: string, role: 'user' | 'manager' | null = 'user'): SupportTicket => ({
  id,
  eventId: 'e',
  eventTitle: 'E',
  userId: 'u',
  userName: 'U',
  subject: 's',
  status: 'open',
  createdAt: last,
  updatedAt: last,
  lastMessageAt: last,
  lastMessage: role ? { body: 'x', senderRole: role, createdAt: last } : null,
  messageCount: 1,
})

describe('messages', () => {
  it('sorts oldest first with id tiebreak', () => {
    const out = sortMessages([
      msg(3, '2026-01-01T10:00:00Z'),
      msg(2, '2026-01-01T10:00:00Z'),
      msg(1, '2026-01-01T09:00:00Z'),
    ])
    expect(out.map((m) => m.id)).toEqual([1, 2, 3])
  })
  it('drops optimistic copies once the server returns the clientId', () => {
    const local = [{ ...msg(-1, '2026-01-01T10:00:01Z', { clientId: 'c1', senderRole: 'manager' as const }), pending: true }]
    const server = [msg(5, '2026-01-01T10:00:00Z', { clientId: 'c1', senderRole: 'manager' })]
    const out = mergeMessages(server, local)
    expect(out).toHaveLength(1)
    expect(out[0].id).toBe(5)
    expect(out[0]).not.toHaveProperty('pending')
  })
  it('keeps still-pending local messages after server ones', () => {
    const local = [{ ...msg(-1, '2026-01-01T10:00:05Z', { clientId: 'c2' }), pending: true }]
    const out = mergeMessages([msg(1, '2026-01-01T10:00:00Z')], local)
    expect(out.map((m) => m.id)).toEqual([1, -1])
  })
  it('does not duplicate by id', () => {
    expect(mergeMessages([msg(1, '2026-01-01T10:00:00Z')], [msg(1, '2026-01-01T10:00:00Z')])).toHaveLength(1)
  })
})

describe('tickets', () => {
  it('sorts newest activity first', () => {
    const out = sortTickets([ticket(1, '2026-01-01T00:00:00Z'), ticket(2, '2026-02-01T00:00:00Z')])
    expect(out.map((t) => t.id)).toEqual([2, 1])
  })
  it('unread logic', () => {
    const t = ticket(1, '2026-01-02T00:00:00Z')
    expect(isUnread(t, {})).toBe(true)
    expect(isUnread(t, { '1': '2026-01-02T00:00:00Z' })).toBe(false)
    expect(isUnread(t, { '1': '2026-01-01T00:00:00Z' })).toBe(true)
    expect(isUnread(ticket(1, '2026-01-02T00:00:00Z', 'manager'), {})).toBe(false)
    expect(isUnread(ticket(1, '2026-01-02T00:00:00Z', null), {})).toBe(false)
    expect(countUnread([t, ticket(2, '2026-01-02T00:00:00Z', 'manager')], {})).toBe(1)
  })
  it('totals open counts', () => {
    expect(totalOpen([{ openCount: 2 }, { openCount: 3 }])).toBe(5)
  })
})

describe('url selection', () => {
  it('parses event, ticket and status', () => {
    expect(parseSelection(new URLSearchParams('event=ev1&ticket=12&status=closed'))).toEqual({
      event: 'ev1',
      ticket: '12',
      status: 'closed',
    })
  })
  it('defaults and ignores bad input', () => {
    expect(parseSelection(new URLSearchParams(''))).toEqual({ event: null, ticket: null, status: 'open' })
    expect(parseSelection(new URLSearchParams('ticket=5'))).toMatchObject({ event: null, ticket: null })
    expect(parseSelection(new URLSearchParams('event=a&ticket=x&status=zzz'))).toEqual({
      event: 'a',
      ticket: null,
      status: 'open',
    })
  })
  it('round-trips', () => {
    const s = { event: 'ev1', ticket: '3', status: 'all' as const }
    expect(parseSelection(buildSearch(s))).toEqual(s)
    expect(buildSearch({ event: 'ev1', status: 'open' }).toString()).toBe('event=ev1')
    expect(buildSearch({}).toString()).toBe('')
  })
})

describe('misc', () => {
  it('filters events by title or venue', () => {
    const l = [
      { title: 'Hack Night', venue: 'Lab' },
      { title: 'Gala', venue: 'Main Hall' },
    ]
    expect(filterEvents(l, 'hall')).toHaveLength(1)
    expect(filterEvents(l, ' ')).toHaveLength(2)
  })
  it('formats relative time', () => {
    const now = Date.parse('2026-01-01T12:00:00Z')
    expect(relativeTime('2026-01-01T11:59:50Z', now)).toBe('now')
    expect(relativeTime('2026-01-01T11:30:00Z', now)).toBe('30m')
    expect(relativeTime('2025-12-30T12:00:00Z', now)).toBe('2d')
    expect(relativeTime(null, now)).toBe('')
  })
})
