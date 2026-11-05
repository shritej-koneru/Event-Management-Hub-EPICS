import { useCallback, useEffect, useRef, useState } from 'react'

import { apiErrorMessage } from './manage'

interface Result<T> {
  key: string
  data?: T
  error?: string
}

/**
 * GET helper: re-fetches when `key` changes or `reload()` is called. Previous data stays
 * visible while a refresh is in flight (`loading` is true only until the first answer
 * for the current key).
 */
export function useFetch<T>(load: (signal: AbortSignal) => Promise<T>, key = '') {
  const [tick, setTick] = useState(0)
  const [result, setResult] = useState<Result<T> | null>(null)
  const loadRef = useRef(load)
  const fullKey = `${key}#${tick}`

  useEffect(() => {
    loadRef.current = load
  })

  useEffect(() => {
    const ac = new AbortController()
    loadRef
      .current(ac.signal)
      .then((data) => setResult({ key: fullKey, data }))
      .catch((err: unknown) => {
        if (ac.signal.aborted) return
        setResult((prev) => ({ key: fullKey, data: prev?.data, error: apiErrorMessage(err) }))
      })
    return () => ac.abort()
  }, [fullKey])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return {
    data: result?.data,
    error: result && result.key === fullKey ? result.error : undefined,
    loading: result?.key !== fullKey,
    reload,
  }
}
