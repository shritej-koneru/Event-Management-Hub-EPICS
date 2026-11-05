import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { X } from 'lucide-react'

import { buttonIcon } from './styles'

interface SheetProps {
  title: string
  onClose: () => void
  children: ReactNode
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** Right-side sheet (full width on phones). Escape closes; Tab stays inside; focus starts on the first field. */
export default function Sheet({ title, onClose, children }: SheetProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const closeFn = useRef(onClose)

  useEffect(() => {
    closeFn.current = onClose
  })

  useEffect(() => {
    const panel = panelRef.current
    const previous = document.activeElement as HTMLElement | null
    const first = panel?.querySelector<HTMLElement>('form input, form select, form textarea')
    ;(first ?? panel?.querySelector<HTMLElement>(FOCUSABLE))?.focus()

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        closeFn.current()
        return
      }
      if (e.key !== 'Tab' || !panel) return
      const items = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)]
      if (items.length === 0) return
      const head = items[0]
      const tail = items[items.length - 1]
      if (e.shiftKey && document.activeElement === head) {
        e.preventDefault()
        tail.focus()
      } else if (!e.shiftKey && document.activeElement === tail) {
        e.preventDefault()
        head.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      previous?.focus?.()
    }
  }, [])

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-ink/40">
      <div className="absolute inset-0" aria-hidden onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="relative flex h-full w-full max-w-md flex-col border-l border-line bg-surface shadow-overlay"
      >
        <div className="flex items-center justify-between border-b border-line px-5 py-3">
          <h2 className="text-heading font-semibold text-ink">{title}</h2>
          <button type="button" className={buttonIcon} aria-label="Close" onClick={onClose}>
            <X className="h-[18px] w-[18px]" strokeWidth={1.75} aria-hidden />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
