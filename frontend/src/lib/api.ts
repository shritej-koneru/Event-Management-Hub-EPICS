/** Tiny typed fetch wrapper for the Event Hub REST API (relative `/api` URLs). */

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details?: unknown

  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

interface Session {
  token: string | null
  onUnauthorized: (() => void) | null
}

const session: Session = { token: null, onUnauthorized: null }

export function setAuthToken(token: string | null): void {
  session.token = token
}

export function getAuthToken(): string | null {
  return session.token
}

/** Called when the server answers 401 to a request that carried a token. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  session.onUnauthorized = handler
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  query?: Record<string, string | number | undefined>
  signal?: AbortSignal
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = `/api${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal } = options
  const sentToken = session.token
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (sentToken) headers.Authorization = `Bearer ${sentToken}`

  let res: Response
  try {
    res = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Check your connection.')
  }

  let payload: unknown = null
  const text = await res.text()
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = null
    }
  }

  if (!res.ok) {
    const data = (payload ?? {}) as { error?: unknown; code?: unknown; details?: unknown }
    const message =
      typeof data.error === 'string' && data.error ? data.error : `Request failed (${res.status})`
    const code = typeof data.code === 'string' ? data.code : `HTTP_${res.status}`
    // Only treat as "session expired" when we actually presented a token that is
    // still the current one (avoids logging out a fresh login on a stale response).
    if (res.status === 401 && sentToken && sentToken === session.token) {
      session.onUnauthorized?.()
    }
    throw new ApiError(res.status, code, message, data.details)
  }

  return payload as T
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong'
}
