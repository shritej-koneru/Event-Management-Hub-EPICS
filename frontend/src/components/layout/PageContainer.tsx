import type { ReactNode } from 'react'

interface PageContainerProps {
  title?: string
  description?: string
  /** Primary action(s), right-aligned in the header. */
  actions?: ReactNode
  children: ReactNode
  className?: string
}

/** Content column (max ~1200px) with the standard page header: title + description left, action right. */
export default function PageContainer({
  title,
  description,
  actions,
  children,
  className = '',
}: PageContainerProps) {
  return (
    <div className="mx-auto w-full min-w-0 max-w-[1200px] px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
      {(title || actions) && (
        <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            {title && <h1 className="text-title font-semibold text-ink">{title}</h1>}
            {description && <p className="text-body text-ink-2">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </div>
      )}
      <div className={className}>{children}</div>
    </div>
  )
}
