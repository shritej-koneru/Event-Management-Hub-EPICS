/** AI helpers for the manager console. Everything here only SUGGESTS: nothing is saved or published automatically. */
import { useEffect, useState } from 'react'

import { api } from './api'

export interface AiStatus {
  enabled: boolean
}

/** True only when the server says AI is configured. While loading, or if the call fails, the AI buttons stay hidden. */
export function useAiEnabled(): boolean {
  const [enabled, setEnabled] = useState(false)
  useEffect(() => {
    const ac = new AbortController()
    api<AiStatus>('/ai/status', { signal: ac.signal })
      .then((s) => setEnabled(s.enabled === true))
      .catch(() => setEnabled(false))
    return () => ac.abort()
  }, [])
  return enabled
}

export interface EventDraft {
  title: string
  description: string
  tags: string[]
}

interface DraftReply<T> {
  draft: T
  status: 'DRAFT_REQUIRES_REVIEW'
}

export async function draftEventWithAi(brief: string): Promise<EventDraft> {
  const r = await api<DraftReply<EventDraft>>('/ai/manage/draft', { method: 'POST', body: { kind: 'event', brief } })
  return r.draft
}

export async function draftAnnouncementWithAi(brief: string, eventId?: string): Promise<string> {
  const r = await api<DraftReply<{ message: string }>>('/ai/manage/draft', {
    method: 'POST',
    body: { kind: 'announcement', brief, ...(eventId ? { eventId } : {}) },
  })
  return r.draft.message
}

export interface CategorySuggestion {
  category: string
  reason: string
  aiGenerated: boolean
}

export function suggestTicketCategory(ticketId: string | number): Promise<CategorySuggestion> {
  return api<CategorySuggestion>(`/ai/manage/tickets/${encodeURIComponent(String(ticketId))}/category`, { method: 'POST' })
}
