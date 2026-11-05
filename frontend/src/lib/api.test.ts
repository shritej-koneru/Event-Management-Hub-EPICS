import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { api, ApiError, setAuthToken, setUnauthorizedHandler } from './api'

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn(async () => new Response(JSON.stringify(body), { status }))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('api()', () => {
  beforeEach(() => {
    setAuthToken(null)
    setUnauthorizedHandler(null)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('sends the bearer token, JSON body and query string', async () => {
    const fetchMock = mockFetch(200, { ok: true })
    setAuthToken('tok')
    await api('/events', { method: 'POST', body: { a: 1 }, query: { q: 'x y', skip: undefined } })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/events?q=x+y')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok')
    expect(init.body).toBe('{"a":1}')
  })

  it('maps server errors to ApiError with code and message', async () => {
    mockFetch(409, { error: 'Duplicate', code: 'DUPLICATE_EVENT' })
    const err = await api('/events', { method: 'POST', body: {} }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 409, code: 'DUPLICATE_EVENT', message: 'Duplicate' })
  })

  it('falls back to a generic message for non-JSON error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>', { status: 502 })))
    await expect(api('/x')).rejects.toMatchObject({ status: 502, code: 'HTTP_502' })
  })

  it('maps network failures to NETWORK_ERROR (status 0)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('failed')
      }),
    )
    await expect(api('/x')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })

  it('calls the unauthorized handler on 401 when a token was sent', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    setAuthToken('stale')
    mockFetch(401, { error: 'bad', code: 'INVALID_TOKEN' })
    await expect(api('/auth/me')).rejects.toMatchObject({ status: 401 })
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('does not call the handler on 401 without a token (wrong password)', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mockFetch(401, { error: 'Invalid credentials', code: 'INVALID_CREDENTIALS' })
    await expect(api('/auth/login', { method: 'POST', body: {} })).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    })
    expect(handler).not.toHaveBeenCalled()
  })
})
