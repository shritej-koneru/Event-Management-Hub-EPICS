import type { ReactNode } from 'react'

import { noticeClass } from './styles'

export function Loading({ label = 'Loading...' }: { label?: string }) {
  return (
    <p role="status" className="py-8 text-center text-small text-ink-2">
      {label}
    </p>
  )
}

export function ErrorNote({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className={`${noticeClass('danger')} mb-3`}>
      {children}
    </p>
  )
}

/** Positive confirmation (role=status so screen readers announce it). */
export function SuccessNote({ children }: { children: ReactNode }) {
  return (
    <p role="status" className={`${noticeClass('success')} mb-3`}>
      {children}
    </p>
  )
}

/** One line of ink-2, optionally followed by a Quiet action. */
export function Empty({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-1 px-4 py-8 text-center">
      <p className="text-body text-ink-2">{children}</p>
      {action}
    </div>
  )
}
