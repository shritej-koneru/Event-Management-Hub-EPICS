import { useCallback, useEffect, useMemo, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { ArrowLeft, Lock, LockOpen, Send } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { Empty, ErrorNote, Loading } from '@/components/Feedback'
import PageContainer from '@/components/layout/PageContainer'
import {
  badgeClass,
  buttonIcon,
  buttonPrimary,
  buttonSecondary,
  chipClass,
  errorTextClass,
  inputClass,
  textareaClass,
} from '@/components/styles'
import { api } from '@/lib/api'
import { suggestTicketCategory, useAiEnabled } from '@/lib/ai'
import type { CategorySuggestion } from '@/lib/ai'
import { formatDate, formatDateTime } from '@/lib/format'
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

const rowClass = (active: boolean) =>
  `relative block w-full cursor-pointer border-b border-line px-4 py-3 text-left transition-colors duration-150 ease-out ${
    active
      ? 'bg-accent-soft before:absolute before:inset-y-0 before:left-0 before:w-0.5 before:bg-accent'
      : 'hover:bg-paper'
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
  const aiEnabled = useAiEnabled()
  const [suggesting, setSuggesting] = useState(false)
  const [suggestion, setSuggestion] = useState<(CategorySuggestion & { ticketId: string }) | null>(null)

  /** On-demand only; the category is shown, never stored. */
  async function suggestCategory() {
    if (!sel.ticket) return
    const ticketId = sel.ticket
    setSuggesting(true)
    setActionError(null)
    try {
      setSuggestion({ ...(await suggestTicketCategory(ticketId)), ticketId })
    } catch (err) {
      setActionError(apiErrorMessage(err))
    } finally {
      setSuggesting(false)
    }
  }

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
    <PageContainer title="Help desk" description="Chats from attendees, grouped by your events">
      <div className="grid grid-cols-1 h-[calc(100dvh-17rem)] min-h-[420px] overflow-hidden rounded-card border border-line bg-surface lg:h-[calc(100vh-14rem)] lg:grid-cols-[260px_300px_minmax(0,1fr)] lg:divide-x lg:divide-line">
        {/* Events */}
        <section aria-label="Events" className={`${paneVis(showEvents)} min-h-0 flex-col overflow-hidden`}>
          <div className="border-b border-line p-3">
            <input
              type="search"
              aria-label="Search events"
              placeholder="Search events"
              className={inputClass}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {events.error && (
              <div className="p-3 pb-0">
                <ErrorNote>{events.error}</ErrorNote>
              </div>
            )}
            {events.loading && <Loading />}
            {!events.loading && !events.error && eventList.length === 0 && <Empty>No events found.</Empty>}
            {eventList.map((ev) => (
              <button
                key={ev.id}
                type="button"
                aria-current={ev.id === sel.event ? 'true' : undefined}
                className={rowClass(ev.id === sel.event)}
                onClick={() => go({ event: ev.id, ticket: null })}
              >
                <span className="flex items-start justify-between gap-2">
                  <span className="text-body font-medium text-ink">{ev.title}</span>
                  {ev.openCount > 0 && (
                    <span className={`${badgeClass} shrink-0`} aria-label={`${ev.openCount} open`}>
                      {ev.openCount}
                    </span>
                  )}
                </span>
                <span className="block text-small text-ink-2">
                  {formatDate(ev.date)} · {ev.venue}
                </span>
                <span className="block text-caption font-medium text-ink-3">
                  {ev.openCount} open / {ev.totalCount} total
                  {ev.lastMessageAt ? ` · ${relativeTime(ev.lastMessageAt)}` : ''}
                </span>
              </button>
            ))}
          </div>
        </section>

        {/* Chats of the selected event */}
        <section aria-label="Chats" className={`${paneVis(showTickets)} min-h-0 flex-col overflow-hidden`}>
          {!sel.event ? (
            <Empty>Select an event to see its chats.</Empty>
          ) : (
            <>
              <div className="flex items-center gap-2 border-b border-line p-3">
                <button
                  type="button"
                  className={`${buttonIcon} lg:hidden`}
                  aria-label="Back to events"
                  onClick={() => go({ event: null, ticket: null })}
                >
                  <ArrowLeft className="h-[18px] w-[18px]" strokeWidth={1.75} aria-hidden />
                </button>
                <h2 className="truncate text-heading font-semibold text-ink">{selectedEvent?.title ?? 'Event'}</h2>
              </div>
              <div className="border-b border-line p-3">
                <div
                  role="group"
                  aria-label="Filter chats"
                  className="flex divide-x divide-line-strong overflow-hidden rounded-control border border-line-strong"
                >
                  {STATUS_FILTERS.map((f: StatusFilter) => (
                    <button
                      key={f}
                      type="button"
                      aria-pressed={sel.status === f}
                      className={`h-8 flex-1 text-small font-medium capitalize transition-colors duration-150 ease-out pointer-coarse:h-11 ${
                        sel.status === f ? 'bg-accent-soft text-accent' : 'bg-surface text-ink-2 hover:bg-paper'
                      }`}
                      onClick={() => go({ status: f, ticket: null })}
                    >
                      {f}
                    </button>
                  ))}
                </div>
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto">
                {tickets.error && (
                  <div className="p-3 pb-0">
                    <ErrorNote>{tickets.error}</ErrorNote>
                  </div>
                )}
                {tickets.loading && <Loading />}
                {!tickets.loading && !tickets.error && ticketList.length === 0 && (
                  <Empty>No {sel.status === 'all' ? '' : sel.status} chats.</Empty>
                )}
                {ticketList.map((t) => {
                  const active = String(t.id) === sel.ticket
                  const unread = !active && isUnread(t, seen)
                  return (
                    <button
                      key={t.id}
                      type="button"
                      aria-current={active ? 'true' : undefined}
                      className={`${rowClass(active)} ${unread ? 'bg-warning-soft before:absolute before:inset-y-0 before:left-0 before:w-0.5 before:bg-warning' : ''}`}
                      onClick={() => go({ ticket: String(t.id) })}
                    >
                      <span className="flex items-center justify-between gap-2">
                        <span className={`truncate text-body text-ink ${unread ? 'font-semibold' : 'font-medium'}`}>
                          {t.userName}
                          {unread && <span className="sr-only"> (needs a reply)</span>}
                        </span>
                        <span className="shrink-0 text-caption font-medium text-ink-3">{relativeTime(t.lastMessageAt)}</span>
                      </span>
                      <span className="block truncate text-small font-medium text-ink">{t.subject}</span>
                      <span className="block truncate text-small text-ink-2">
                        {t.lastMessage ? `${t.lastMessage.senderRole === 'manager' ? 'You: ' : ''}${t.lastMessage.body}` : 'No messages'}
                      </span>
                      <span className={`${chipClass(t.status === 'open' ? 'success' : 'neutral')} mt-1.5 capitalize`}>
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
        <section aria-label="Conversation" className={`${paneVis(!!sel.ticket)} min-h-0 min-w-0 flex-col overflow-hidden`}>
          {!sel.ticket ? (
            <Empty>Select a chat to read and reply.</Empty>
          ) : (
            <>
              <div className="flex items-center gap-2 border-b border-line p-3">
                <button
                  type="button"
                  className={`${buttonIcon} lg:hidden`}
                  aria-label="Back to chats"
                  onClick={() => go({ ticket: null })}
                >
                  <ArrowLeft className="h-[18px] w-[18px]" strokeWidth={1.75} aria-hidden />
                </button>
                <div className="min-w-0 flex-1">
                  <h2 className="truncate text-heading font-semibold text-ink">
                    {threadData?.ticket.subject ?? 'Chat'}
                  </h2>
                  {threadData && (
                    <p className="flex items-center gap-2 text-small text-ink-2">
                      <span className="truncate">
                        {threadData.ticket.userName} · {threadData.ticket.eventTitle}
                      </span>
                      <span className={`${chipClass(threadData.ticket.status === 'open' ? 'success' : 'neutral')} capitalize`}>
                        {threadData.ticket.status}
                      </span>
                    </p>
                  )}
                  {suggestion && String(suggestion.ticketId) === sel.ticket && (
                    <p role="status" className="mt-1 text-small text-ink-2">
                      Suggested category: <strong className="capitalize">{suggestion.category}</strong>
                      {suggestion.aiGenerated ? ' (AI suggestion)' : ' (keyword match, AI unavailable)'}. Nothing is saved.
                    </p>
                  )}
                </div>
                {threadData && aiEnabled && (
                  <button type="button" className={buttonSecondary} disabled={suggesting} onClick={() => void suggestCategory()}>
                    {suggesting ? 'Thinking...' : 'Suggest category'}
                  </button>
                )}
                {threadData && (
                  <button
                    type="button"
                    className={buttonSecondary}
                    disabled={busy}
                    onClick={() => setStatus(threadData.ticket.status === 'open' ? 'closed' : 'open')}
                  >
                    {threadData.ticket.status === 'open' ? (
                      <>
                        <Lock className="h-4 w-4" strokeWidth={1.75} aria-hidden /> Close
                      </>
                    ) : (
                      <>
                        <LockOpen className="h-4 w-4" strokeWidth={1.75} aria-hidden /> Reopen
                      </>
                    )}
                  </button>
                )}
              </div>

              <div className="min-h-0 flex-1 space-y-4 overflow-y-auto bg-paper p-4" aria-live="polite">
                {thread.error && <ErrorNote>{thread.error}</ErrorNote>}
                {!threadData && !thread.error && <Loading />}
                {threadData && messages.length === 0 && <Empty>No messages yet.</Empty>}
                {messages.map((m) => {
                  const mine = m.senderRole === 'manager'
                  return (
                    <div key={m.clientId ?? m.id} className={`flex flex-col ${mine ? 'items-end' : 'items-start'}`}>
                      <p className="mb-1 max-w-[80%] text-caption font-medium text-ink-3">
                        {mine ? m.senderName || 'Manager' : m.senderName}
                        {' · '}
                        {m.failed ? (
                          <button type="button" className="font-medium text-danger underline" onClick={() => void postReply(m)}>
                            Failed - retry
                          </button>
                        ) : m.pending ? (
                          'Sending...'
                        ) : (
                          formatDateTime(m.createdAt)
                        )}
                      </p>
                      <div
                        className={`max-w-[80%] rounded-card border px-3 py-2 text-body text-ink ${
                          mine ? 'rounded-br-[2px] border-accent/20 bg-accent-soft' : 'rounded-bl-[2px] border-line bg-surface'
                        } ${m.failed ? 'border-danger' : ''} ${m.pending ? 'opacity-70' : ''}`}
                      >
                        <p className="whitespace-pre-wrap break-words">{m.body}</p>
                      </div>
                    </div>
                  )
                })}
              </div>

              <div className="border-t border-line bg-surface p-3">
                {actionError && (
                  <p role="alert" className={`${errorTextClass} mb-2`}>
                    {actionError}
                  </p>
                )}
                <div className="flex items-end gap-2">
                  <label className="min-w-0 flex-1">
                    <span className="sr-only">Reply</span>
                    <textarea
                      className={`${textareaClass} h-[72px] min-h-0 resize-none`}
                      placeholder="Write a reply (Enter to send, Shift+Enter for a new line)"
                      maxLength={REPLY_MAX}
                      value={draft}
                      onChange={(e) => setDraft(e.target.value)}
                      onKeyDown={onKeyDown}
                    />
                  </label>
                  <button
                    type="button"
                    className={`${buttonPrimary} w-9 px-0! pointer-coarse:w-11`}
                    aria-label="Send"
                    disabled={!draft.trim() || !threadData}
                    onClick={send}
                  >
                    <Send className="h-4 w-4" strokeWidth={1.75} aria-hidden />
                  </button>
                </div>
                <p
                  className={`mt-1 text-right text-caption font-medium ${REPLY_MAX - draft.length < 50 ? 'text-danger' : 'text-ink-3'}`}
                >
                  {draft.length}/{REPLY_MAX}
                </p>
              </div>
            </>
          )}
        </section>
      </div>
    </PageContainer>
  )
}
