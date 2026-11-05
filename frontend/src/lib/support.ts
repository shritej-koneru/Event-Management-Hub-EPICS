/** Pure helpers for the Help desk page (unit-tested in support.test.ts). */
import type { SupportMessage, SupportTicket } from './types'

export const REPLY_MAX = 1000

export type StatusFilter = 'open' | 'closed' | 'all'
export const STATUS_FILTERS: StatusFilter[] = ['open', 'closed', 'all']

/** A message shown before the server confirmed it. */
export interface LocalMessage extends SupportMessage {
  pending?: boolean
  failed?: boolean
}

const time = (iso: string): number => {
  const t = Date.parse(iso)
  return Number.isNaN(t) ? 0 : t
}

/** Oldest first; ties broken by numeric id. */
export function sortMessages<T extends SupportMessage>(list: T[]): T[] {
  return [...list].sort((a, b) => time(a.createdAt) - time(b.createdAt) || Number(a.id) - Number(b.id))
}

/**
 * Merge server messages with optimistic local ones. A local message is dropped once
 * the server list contains the same clientId (or same id); server copies win.
 */
export function mergeMessages(server: SupportMessage[], local: LocalMessage[]): LocalMessage[] {
  const ids = new Set(server.map((m) => String(m.id)))
  const clientIds = new Set(server.map((m) => m.clientId).filter((c): c is string => !!c))
  const extra = local.filter((m) => !ids.has(String(m.id)) && !(m.clientId && clientIds.has(m.clientId)))
  return sortMessages<LocalMessage>([...server, ...extra])
}

/** Newest activity first. */
export function sortTickets<T extends Pick<SupportTicket, 'lastMessageAt' | 'id'>>(list: T[]): T[] {
  return [...list].sort((a, b) => time(b.lastMessageAt) - time(a.lastMessageAt) || Number(b.id) - Number(a.id))
}

/**
 * A chat is highlighted when the attendee spoke last and the manager has not
 * viewed it since. `seen` maps ticket id -> lastMessageAt at the time it was opened
 * (in memory only; a never-opened chat awaiting a reply counts as new).
 */
export function isUnread(ticket: SupportTicket, seen: Record<string, string>): boolean {
  if (ticket.lastMessage?.senderRole !== 'user') return false
  const s = seen[String(ticket.id)]
  return s === undefined || time(ticket.lastMessageAt) > time(s)
}

export function countUnread(tickets: SupportTicket[], seen: Record<string, string>): number {
  return tickets.filter((t) => isUnread(t, seen)).length
}

/** Total open chats across events (nav badge). */
export function totalOpen(events: { openCount: number }[]): number {
  return events.reduce((n, e) => n + (Number.isFinite(e.openCount) ? e.openCount : 0), 0)
}

export interface Selection {
  event: string | null
  ticket: string | null
  status: StatusFilter
}

/** Parse `?event=&ticket=&status=`; a ticket without an event is ignored. */
export function parseSelection(params: URLSearchParams): Selection {
  const event = params.get('event')?.trim() || null
  const ticketRaw = params.get('ticket')?.trim() || ''
  const ticket = event && /^\d+$/.test(ticketRaw) ? ticketRaw : null
  const st = params.get('status')
  const status: StatusFilter = st === 'closed' || st === 'all' ? st : 'open'
  return { event, ticket, status }
}

/** Inverse of parseSelection; the default `status=open` is omitted. */
export function buildSearch(sel: Partial<Selection>): URLSearchParams {
  const p = new URLSearchParams()
  if (sel.event) {
    p.set('event', sel.event)
    if (sel.ticket) p.set('ticket', sel.ticket)
    if (sel.status && sel.status !== 'open') p.set('status', sel.status)
  }
  return p
}

/** Case-insensitive search on title/venue. */
export function filterEvents<T extends { title: string; venue: string }>(list: T[], q: string): T[] {
  const n = q.trim().toLowerCase()
  if (!n) return list
  return list.filter((e) => e.title.toLowerCase().includes(n) || e.venue.toLowerCase().includes(n))
}

export function newClientId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `c-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** "5m", "3h", "2d" style relative time. */
export function relativeTime(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return ''
  const diff = Math.max(0, now - time(iso))
  const m = Math.floor(diff / 60000)
  if (m < 1) return 'now'
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}
