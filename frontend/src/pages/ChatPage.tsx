import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Megaphone, Send } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import { ErrorNote, PageHeading } from '@/components/Feedback'
import { api } from '@/lib/api'
import type { Announcement, ChatMessage, EventBroadcast } from '@/lib/types'
import { useSocket } from '@/realtime/context'

interface Notice {
  key: string
  text: string
  at: string
}

interface ChatAck {
  ok: boolean
  message?: ChatMessage
  error?: { code: string; message: string }
}

const MAX_NOTICES = 20

/** Merge by id (history, live broadcast and ack can all deliver the same row). */
function mergeMessages(prev: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map(prev.map((m) => [m.id, m]))
  for (const m of incoming) byId.set(m.id, m)
  return [...byId.values()].sort((a, b) => a.ts.localeCompare(b.ts) || a.id - b.id)
}

function formatTime(ts: string): string {
  const d = new Date(ts)
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** The relay stores "<title> [evt_id]"; show the readable part only. */
function stripEventId(message: string): string {
  return message.replace(/\s*\[evt_[^\]]+\]\s*$/, '')
}

export default function ChatPage() {
  const { user } = useAuth()
  const { socket, status } = useSocket()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [notices, setNotices] = useState<Notice[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [sendError, setSendError] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  // History + persisted announcements: on first mount and after every reconnect
  // (covers anything missed while offline).
  useEffect(() => {
    if (status !== 'connected') return
    let cancelled = false
    api<{ messages: ChatMessage[] }>('/chat/messages', { query: { limit: 50 } })
      .then((r) => {
        if (cancelled) return
        setLoadError(null)
        setMessages((prev) => mergeMessages(prev, r.messages))
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoadError(err instanceof Error ? err.message : 'Could not load history')
      })
    api<{ announcements: Announcement[] }>('/chat/announcements')
      .then((r) => {
        if (cancelled) return
        const loaded = r.announcements.map<Notice>((a) => ({
          key: `a${a.id}`,
          text: stripEventId(a.message),
          at: a.at,
        }))
        setNotices((prev) => {
          const keys = new Set(prev.map((n) => n.key))
          return [...prev, ...loaded.filter((n) => !keys.has(n.key))].slice(0, MAX_NOTICES)
        })
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [status])

  // Live traffic.
  useEffect(() => {
    if (!socket) return
    const onMessage = (m: ChatMessage) => setMessages((prev) => mergeMessages(prev, [m]))
    const onEvent = (p: EventBroadcast) => {
      const notice: Notice = {
        key: `live-${p.eventId}-${p.reason}-${Date.now()}`,
        text: p.title,
        at: new Date().toISOString(),
      }
      setNotices((prev) => [notice, ...prev].slice(0, MAX_NOTICES))
    }
    const onChatError = (e: { code: string; message: string }) =>
      setSendError(`${e.code}: ${e.message}`)
    socket.on('chat:message', onMessage)
    socket.on('event', onEvent)
    socket.on('chat:error', onChatError)
    return () => {
      socket.off('chat:message', onMessage)
      socket.off('event', onEvent)
      socket.off('chat:error', onChatError)
    }
  }, [socket])

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ block: 'end' })
  }, [messages.length])

  function send(e: FormEvent) {
    e.preventDefault()
    const body = text.trim()
    if (!body || !socket || sending) return
    setSending(true)
    setSendError(null)
    socket
      .timeout(8000)
      .emit(
        'chat:message',
        { text: body, clientId: crypto.randomUUID() },
        (err: Error | null, ack?: ChatAck) => {
          setSending(false)
          if (err) {
            setSendError('The server did not respond. Try again.')
          } else if (ack && !ack.ok) {
            setSendError(`${ack.error?.code ?? 'ERROR'}: ${ack.error?.message ?? 'Message rejected'}`)
          } else {
            setText('')
            if (ack?.message) {
              const sent = ack.message
              setMessages((prev) => mergeMessages(prev, [sent]))
            }
          }
        },
      )
  }

  const statusLabel =
    status === 'connected' ? 'Live' : status === 'connecting' ? 'Connecting...' : 'Offline'

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Chat" />
      <p className="mb-4 text-[13px] text-white/70">
        Campus-wide chat, shared with the mobile app.{' '}
        <span role="status" className="font-bold text-white">
          {statusLabel}
        </span>
      </p>

      {notices.length > 0 && (
        <section
          aria-label="Announcements"
          className="mb-4 rounded-[18px] bg-white/16 p-3 text-sm text-white"
        >
          <h2 className="mb-1.5 flex items-center gap-1.5 text-xs font-bold uppercase">
            <Megaphone className="h-4 w-4" aria-hidden />
            Announcements
          </h2>
          <ul className="flex max-h-28 flex-col gap-1 overflow-y-auto">
            {notices.map((n) => (
              <li key={n.key}>
                {n.text} <span className="text-white/60">{formatTime(n.at)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {loadError && <ErrorNote>{loadError}</ErrorNote>}

      <ul
        aria-label="Messages"
        className="mb-3 flex h-[380px] flex-col gap-2 overflow-y-auto rounded-[18px] bg-[rgba(255,255,255,0.97)] p-3"
      >
        {messages.length === 0 && (
          <li className="py-6 text-center text-sm text-[#77758b]">No messages yet.</li>
        )}
        {messages.map((m) => (
          <li
            key={m.id}
            className={`rounded-[14px] px-3 py-2 text-sm ${
              user && m.author === user.name && !m.bot
                ? 'self-end bg-[#e4ddff]'
                : 'self-start bg-[#f4f0ff]'
            }`}
          >
            <div className="text-[11px] font-bold text-[#5c3ce8]">
              {m.author}
              {m.bot ? ' (bot)' : ''}
              {m.room ? ` @ ${m.room}` : ''}{' '}
              <time dateTime={m.ts} className="font-normal text-[#77758b]">
                {formatTime(m.ts)}
              </time>
            </div>
            <div className="break-words text-[#25204c]">{m.text}</div>
          </li>
        ))}
        <div ref={bottomRef} />
      </ul>

      {sendError && <ErrorNote>{sendError}</ErrorNote>}

      {user ? (
        <form onSubmit={send} className="mt-3 flex gap-2">
          <input
            aria-label="Message"
            value={text}
            maxLength={500}
            onChange={(e) => setText(e.target.value)}
            placeholder="Type a message..."
            className="h-12 min-w-0 flex-1 rounded-[13px] border border-white/25 bg-white/16 px-3.5 text-[15px] text-white outline-none placeholder:text-white/70 focus:border-white/50"
          />
          <button
            type="submit"
            aria-label="Send message"
            disabled={sending || status !== 'connected' || !text.trim()}
            className="flex h-12 w-12 cursor-pointer items-center justify-center rounded-[13px] border-none bg-white text-[#5c3ce8] disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Send className="h-5 w-5" aria-hidden />
          </button>
        </form>
      ) : (
        <p className="mt-3 text-sm text-white/80">
          <Link to="/login" className="font-bold underline">
            Sign in
          </Link>{' '}
          to send messages. You can still read the chat.
        </p>
      )}
    </div>
  )
}
