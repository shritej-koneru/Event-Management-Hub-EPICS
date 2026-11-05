import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

import { dangerSolidClass, smallButtonClass } from './styles'

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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        className="w-full max-w-sm rounded-[22px] bg-white p-5 text-[#282348] shadow-xl"
      >
        <h2 id="confirm-title" className="mb-2 text-lg font-bold">
          {title}
        </h2>
        <div className="text-sm text-[#4b476b]">{children}</div>
        {error && (
          <p role="alert" className="mt-3 text-sm text-red-600">
            {error}
          </p>
        )}
        <div className="mt-4 flex justify-end gap-2">
          <button ref={cancelRef} type="button" className={smallButtonClass} onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className={dangerSolidClass} disabled={busy} onClick={onConfirm}>
            {busy ? 'Working...' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
