import { useCallback, useEffect, useMemo, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { ArrowLeft, Lock, LockOpen, Send } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { Empty, ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import { compactPrimaryClass, inputClass, smallButtonClass } from '@/components/styles'
import { api } from '@/lib/api'
import { apiErrorMessage } from '@/lib/manage'
import {
  REPLY_MAX,
  STATUS_FILTERS,
  buildSearch,
  filterEvents,
  isUnread,
  mergeMessages,
  newClientId,
  parseSelection,
  relativeTime,
  sortTickets,
} from '@/lib/support'
import type { LocalMessage, StatusFilter } from '@/lib/support'
import type { SupportEventSummary, SupportMessage, SupportTicket, TicketStatus } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'
import { useSocket } from '@/realtime/context'

const paneClass = 'min-h-0 rounded-[22px] bg-[rgba(255,255,255,0.97)] p-3 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]'
const rowClass = (active: boolean) =>
  `block w-full cursor-pointer rounded-[14px] border px-3 py-2.5 text-left text-sm transition ${
    active ? 'border-[#6145ef] bg-[#f0ecff]' : 'border-transparent hover:bg-[#f7f5ff]'
  }`

export default function HelpDeskPage() {
  const { user } = useAuth()
  const { socket } = useSocket()
  const [params, setParams] = useSearchParams()
  const sel = useMemo(() => parseSelection(params), [params])

  const [search, setSearch] = useState('')
  const [seen, setSeen] = useState<Record<string, string>>({})
  const [local, setLocal] = useState<LocalMessage[]>([])
  const [draft, setDraft] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const events = useFetch((signal) =>
    api<{ events: SupportEventSummary[] }>('/manage/support/events', { signal }),
  )
  const tickets = useFetch(
    (signal) =>
      sel.event
        ? api<{ tickets: SupportTicket[] }>('/manage/support/tickets', {
            query: { eventId: sel.event, status: sel.status },
            signal,
          })
        : Promise.resolve({ tickets: [] as SupportTicket[] }),
    `${sel.event}|${sel.status}`,
  )
  const thread = useFetch(
    (signal) =>
      sel.ticket
        ? api<{ ticket: SupportTicket; messages: SupportMessage[] }>(
            `/manage/support/tickets/${encodeURIComponent(sel.ticket)}/messages`,
            { signal },
          )
        : Promise.resolve(null),
    `${sel.ticket}`,
  )

  const reloadEvents = events.reload
  const reloadTickets = tickets.reload
  const reloadThread = thread.reload

  // Live updates: refetch everything visible. Polling is a fallback if the socket is down.
  const refreshAll = useCallback(() => {
    reloadEvents()
    reloadTickets()
    reloadThread()
  }, [reloadEvents, reloadTickets, reloadThread])

  useEffect(() => {
    if (!socket) return
    socket.on('support:message', refreshAll)
    socket.on('support:ticket', refreshAll)
    return () => {
      socket.off('support:message', refreshAll)
      socket.off('support:ticket', refreshAll)
    }
  }, [socket, refreshAll])

  useEffect(() => {
    if (socket?.connected) return
    const t = window.setInterval(refreshAll, 30000)
    return () => window.clearInterval(t)
  }, [socket, refreshAll])

  const eventList = filterEvents(events.data?.events ?? [], search)
  const ticketList = sortTickets(tickets.data?.tickets ?? []).filter((t) => t.eventId === sel.event)
  const selectedEvent = events.data?.events.find((e) => e.id === sel.event)
  const threadData = thread.data && String(thread.data.ticket.id) === sel.ticket ? thread.data : null
  const messages = threadData
    ? mergeMessages(
        threadData.messages,
        local.filter((m) => String(m.ticketId) === sel.ticket),
      )
    : []

  const go = (next: Partial<ReturnType<typeof parseSelection>>) => {
    // Leaving a chat marks it viewed up to its latest activity (in memory only).
    if (sel.ticket && next.ticket !== sel.ticket && threadData) {
      const key = sel.ticket
      const at = threadData.ticket.lastMessageAt
      setSeen((s) => ({ ...s, [key]: at }))
    }
    setActionError(null)
    setDraft('')
    setParams(buildSearch({ ...sel, ...next }))
  }

  async function postReply(m: LocalMessage) {
    setActionError(null)
    setLocal((prev) => prev.map((x) => (x.clientId === m.clientId ? { ...x, pending: true, failed: false } : x)))
    try {
      await api(`/manage/support/tickets/${encodeURIComponent(String(m.ticketId))}/messages`, {
        method: 'POST',
        body: { body: m.body, clientId: m.clientId },
      })
      reloadThread()
      reloadTickets()
      reloadEvents()
    } catch (err) {
      setLocal((prev) => prev.map((x) => (x.clientId === m.clientId ? { ...x, pending: false, failed: true } : x)))
      setActionError(apiErrorMessage(err))
    }
  }

  function send() {
    const body = draft.trim()
    if (!body || body.length > REPLY_MAX || !sel.ticket || !user) return
    const m: LocalMessage = {
      id: -Date.now(),
      ticketId: sel.ticket,
      senderId: user.id,
      senderName: user.name,
      senderRole: 'manager',
      body,
      createdAt: new Date().toISOString(),
      clientId: newClientId(),
      pending: true,
    }
    setLocal((prev) => [...prev, m])
    setDraft('')
    void postReply(m)
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      send()
    }
  }

  async function setStatus(status: TicketStatus) {
    if (!sel.ticket) return
    setBusy(true)
    setActionError(null)
    try {
      await api(`/manage/support/tickets/${encodeURIComponent(sel.ticket)}`, { method: 'PUT', body: { status } })
      reloadThread()
      reloadTickets()
      reloadEvents()
    } catch (err) {
      setActionError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  // Mobile drill-down: show one pane at a time; desktop shows all three.
  const showEvents = !sel.event
  const showTickets = !!sel.event && !sel.ticket
  const paneVis = (visibleOnMobile: boolean) => (visibleOnMobile ? 'flex' : 'hidden lg:flex')

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Help desk" subtitle="Chats from attendees, grouped by your events" />

      <div className="grid gap-4 lg:h-[calc(100vh-190px)] lg:grid-cols-[260px_300px_minmax(0,1fr)]">
        {/* Events */}
        <section aria-label="Events" className={`${paneClass} ${paneVis(showEvents)} flex-col gap-2 overflow-hidden`}>
          <input
            type="search"
            aria-label="Search events"
            placeholder="Search events"
            className={inputClass}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className="min-h-0 flex-1 space-y-1 overflow-y-auto">
            {events.error && <ErrorNote>{events.error}</ErrorNote>}
            {events.loading && <Loading />}
            {!events.loading && !events.error && eventList.length === 0 && (
              <p className="py-6 text-center text-sm text-[#77758b]">No events found.</p>
            )}
            {eventList.map((ev) => (
              <button
                key={ev.id}
                type="button"
                aria-current={ev.id === sel.event ? 'true' : undefined}
                className={rowClass(ev.id === sel.event)}
                onClick={() => go({ event: ev.id, ticket: null })}
              >
                <span className="flex items-start justify-between gap-2">
                  <span className="font-bold">{ev.title}</span>
                  {ev.openCount > 0 && (
                    <span
                      className="shrink-0 rounded-full bg-[#6145ef] px-2 py-0.5 text-[11px] font-bold text-white"
                      aria-label={`${ev.openCount} open`}
                    >
                      {ev.openCount}
                    </span>
                  )}
                </span>
                <span className="block text-xs text-[#77758b]">
                  {ev.date} - {ev.venue}
                </span>
                <span className="block text-xs text-[#77758b]">
                  {ev.openCount} open / {ev.totalCount} total
                  {ev.lastMessageAt ? ` - ${relativeTime(ev.lastMessageAt)}` : ''}
                </span>
              </button>
            ))}
          </div>
        </section>

        {/* Chats of the selected event */}
        <section aria-label="Chats" className={`${paneClass} ${paneVis(showTickets)} flex-col gap-2 overflow-hidden`}>
          {!sel.event ? (
            <p className="py-10 text-center text-sm text-[#77758b]">Select an event to see its chats.</p>
          ) : (
            <>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className={`${smallButtonClass} lg:hidden`}
                  aria-label="Back to events"
                  onClick={() => go({ event: null, ticket: null })}
                >
                  <ArrowLeft className="h-4 w-4" aria-hidden />
                </button>
                <h2 className="truncate text-base font-bold text-[#5c3ce8]">
                  {selectedEvent?.title ?? 'Event'}
                </h2>
              </div>
              <div role="group" aria-label="Filter chats" className="flex gap-1">
                {STATUS_FILTERS.map((f: StatusFilter) => (
                  <button
                    key={f}
                    type="button"
                    aria-pressed={sel.status === f}
                    className={`${smallButtonClass} flex-1 capitalize ${sel.status === f ? 'bg-[#f0ecff]' : ''}`}
                    onClick={() => go({ status: f, ticket: null })}
                  >
                    {f}
                  </button>
                ))}
              </div>
              <div className="min-h-0 flex-1 space-y-1 overflow-y-auto">
                {tickets.error && <ErrorNote>{tickets.error}</ErrorNote>}
                {tickets.loading && <Loading />}
                {!tickets.loading && !tickets.error && ticketList.length === 0 && (
                  <p className="py-6 text-center text-sm text-[#77758b]">No {sel.status === 'all' ? '' : sel.status} chats.</p>
                )}
                {ticketList.map((t) => {
                  const active = String(t.id) === sel.ticket
                  const unread = !active && isUnread(t, seen)
                  return (
                    <button
                      key={t.id}
                      type="button"
                      aria-current={active ? 'true' : undefined}
                      className={`${rowClass(active)} ${unread ? 'border-l-4 !border-l-[#f59e0b] bg-amber-50' : ''}`}
                      onClick={() => go({ ticket: String(t.id) })}
                    >
                      <span className="flex items-center justify-between gap-2">
                        <span className={`truncate ${unread ? 'font-extrabold' : 'font-bold'}`}>{t.userName}</span>
                        <span className="shrink-0 text-xs text-[#77758b]">{relativeTime(t.lastMessageAt)}</span>
                      </span>
                      <span className="block truncate text-xs font-semibold">{t.subject}</span>
                      <span className="block truncate text-xs text-[#77758b]">
                        {t.lastMessage ? `${t.lastMessage.senderRole === 'manager' ? 'You: ' : ''}${t.lastMessage.body}` : 'No messages'}
                      </span>
                      <span
                        className={`mt-1 inline-block rounded-full px-2 py-0.5 text-[10px] font-bold uppercase ${
                          t.status === 'open' ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-700'
                        }`}
                      >
                        {t.status}
                      </span>
                    </button>
                  )
                })}
              </div>
            </>
          )}
        </section>

        {/* Thread */}
        <section
          aria-label="Conversation"
          className={`${paneClass} ${paneVis(!!sel.ticket)} min-w-0 flex-col gap-2 overflow-hidden`}
        >
          {!sel.ticket ? (
            <p className="py-10 text-center text-sm text-[#77758b]">Select a chat to read and reply.</p>
          ) : (
            <>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className={`${smallButtonClass} lg:hidden`}
                  aria-label="Back to chats"
                  onClick={() => go({ ticket: null })}
                >
                  <ArrowLeft className="h-4 w-4" aria-hidden />
                </button>
                <div className="min-w-0 flex-1">
                  <h2 className="truncate text-base font-bold text-[#5c3ce8]">
                    {threadData?.ticket.subject ?? 'Chat'}
                  </h2>
                  {threadData && (
                    <p className="truncate text-xs text-[#77758b]">
                      {threadData.ticket.userName} - {threadData.ticket.eventTitle} - {threadData.ticket.status}
                    </p>
                  )}
                </div>
                {threadData && (
                  <button
                    type="button"
                    className={smallButtonClass}
                    disabled={busy}
                    onClick={() => setStatus(threadData.ticket.status === 'open' ? 'closed' : 'open')}
                  >
                    {threadData.ticket.status === 'open' ? (
                      <>
                        <Lock className="h-3.5 w-3.5" aria-hidden /> Close
                      </>
                    ) : (
                      <>
                        <LockOpen className="h-3.5 w-3.5" aria-hidden /> Reopen
                      </>
                    )}
                  </button>
                )}
              </div>

              <div className="min-h-[240px] flex-1 space-y-2 overflow-y-auto rounded-[14px] bg-[#f7f5ff] p-3" aria-live="polite">
                {thread.error && <ErrorNote>{thread.error}</ErrorNote>}
                {!threadData && !thread.error && <Loading />}
                {threadData && messages.length === 0 && <Empty>No messages yet.</Empty>}
                {messages.map((m) => {
                  const mine = m.senderRole === 'manager'
                  return (
                    <div key={m.clientId ?? m.id} className={`flex ${mine ? 'justify-end' : 'justify-start'}`}>
                      <div
                        className={`max-w-[80%] rounded-[16px] px-3 py-2 text-sm ${
                          mine ? 'bg-[#6145ef] text-white' : 'bg-white text-[#282348] shadow'
                        } ${m.failed ? 'ring-2 ring-red-500' : ''} ${m.pending ? 'opacity-70' : ''}`}
                      >
                        <p className={`text-[11px] font-bold ${mine ? 'text-white/80' : 'text-[#77758b]'}`}>
                          {mine ? m.senderName || 'Manager' : m.senderName}
                        </p>
                        <p className="whitespace-pre-wrap break-words">{m.body}</p>
                        <p className={`mt-0.5 text-[10px] ${mine ? 'text-white/70' : 'text-[#77758b]'}`}>
                          {m.failed ? (
                            <button type="button" className="underline" onClick={() => void postReply(m)}>
                              Failed - retry
                            </button>
                          ) : m.pending ? (
                            'Sending...'
                          ) : (
                            new Date(m.createdAt).toLocaleString()
                          )}
                        </p>
                      </div>
                    </div>
                  )
                })}
              </div>

              {actionError && (
                <p role="alert" className="text-sm text-red-600">
                  {actionError}
                </p>
              )}
              <div className="flex items-end gap-2">
                <label className="min-w-0 flex-1 text-xs font-semibold">
                  <span className="sr-only">Reply</span>
                  <textarea
                    className={`${inputClass} h-20 resize-none py-2`}
                    placeholder="Write a reply (Enter to send, Shift+Enter for a new line)"
                    maxLength={REPLY_MAX}
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={onKeyDown}
                  />
                </label>
                <button
                  type="button"
                  className={compactPrimaryClass}
                  disabled={!draft.trim() || !threadData}
                  onClick={send}
                >
                  <Send className="h-4 w-4" aria-hidden />
                  Send
                </button>
              </div>
              <p className={`text-right text-xs ${REPLY_MAX - draft.length < 50 ? 'text-red-600' : 'text-[#77758b]'}`}>
                {draft.length}/{REPLY_MAX}
              </p>
            </>
          )}
        </section>
      </div>
    </div>
  )
}

