import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

import { buttonDangerSolid, buttonSecondary, errorTextClass, overlayClass } from './styles'

interface ConfirmDialogProps {
  title: string
  children: ReactNode
  confirmLabel: string
  busy?: boolean
  error?: string | null
  onConfirm: () => void
  onCancel: () => void
}

/** Modal confirm for destructive actions. Focus starts on Cancel; Escape cancels. */
export default function ConfirmDialog({
  title,
  children,
  confirmLabel,
  busy,
  error,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const cancelFn = useRef(onCancel)

  useEffect(() => {
    cancelFn.current = onCancel
  })

  useEffect(() => {
    cancelRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') cancelFn.current()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4">
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        className={`${overlayClass} w-full max-w-sm p-5`}
      >
        <h2 id="confirm-title" className="mb-2 text-heading font-semibold text-ink">
          {title}
        </h2>
        <div className="text-body text-ink-2">{children}</div>
        {error && (
          <p role="alert" className={`${errorTextClass} mt-3`}>
            {error}
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <button ref={cancelRef} type="button" className={buttonSecondary} onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className={buttonDangerSolid} disabled={busy} onClick={onConfirm}>
            {busy ? 'Working...' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
