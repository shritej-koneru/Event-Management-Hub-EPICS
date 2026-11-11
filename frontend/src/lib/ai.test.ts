import { afterEach, describe, expect, it, vi } from 'vitest'

import { draftAnnouncementWithAi, draftEventWithAi, suggestTicketCategory } from './ai'
import { apiErrorMessage } from './manage'
import { ApiError } from './api'

function stubFetch(status: number, body: unknown) {
  const fn = vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fn)
  return fn
}

afterEach(() => vi.unstubAllGlobals())

describe('manager AI helpers', () => {
  it('drafts an event from notes and returns only the draft fields', async () => {
    const fn = stubFetch(200, { draft: { title: 'T', description: 'D', tags: ['a'] }, status: 'DRAFT_REQUIRES_REVIEW' })
    expect(await draftEventWithAi('robotics')).toEqual({ title: 'T', description: 'D', tags: ['a'] })
    const [url, init] = fn.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/ai/manage/draft')
    expect(JSON.parse(init.body as string)).toEqual({ kind: 'event', brief: 'robotics' })
  })

  it('drafts an announcement, sending the event id only when given', async () => {
    const fn = stubFetch(200, { draft: { message: 'Doors open at 10:00.' }, status: 'DRAFT_REQUIRES_REVIEW' })
    expect(await draftAnnouncementWithAi('remind', 'evt_1')).toBe('Doors open at 10:00.')
    await draftAnnouncementWithAi('remind')
    const bodies = fn.mock.calls.map((c) => JSON.parse((c as unknown as [string, RequestInit])[1].body as string))
    expect(bodies[0]).toEqual({ kind: 'announcement', brief: 'remind', eventId: 'evt_1' })
    expect(bodies[1]).toEqual({ kind: 'announcement', brief: 'remind' })
  })

  it('asks for a ticket category on demand', async () => {
    const fn = stubFetch(200, { ticketId: 5, category: 'registration', reason: 'x', aiGenerated: true })
    expect((await suggestTicketCategory(5)).category).toBe('registration')
    expect((fn.mock.calls[0] as unknown as [string])[0]).toBe('/api/ai/manage/tickets/5/category')
  })

  it('shows a plain message for provider failures instead of raw errors', async () => {
    stubFetch(503, { error: 'x', code: 'AI_UNAVAILABLE' })
    const err = await draftEventWithAi('x').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(apiErrorMessage(err)).toMatch(/not responding/i)
    expect(apiErrorMessage(new ApiError(502, 'AI_BAD_RESPONSE', 'm'))).toMatch(/discarded/i)
    expect(apiErrorMessage(new ApiError(503, 'AI_DISABLED', 'm'))).toMatch(/not available/i)
  })
})
