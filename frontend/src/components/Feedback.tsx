import type { ReactNode } from 'react'

export function Loading({ label = 'Loading...' }: { label?: string }) {
  return (
    <p role="status" className="py-10 text-center text-sm text-white/70">
      {label}
    </p>
  )
}

export function ErrorNote({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="rounded-[14px] bg-red-500/25 px-4 py-3 text-sm text-white">
      {children}
    </p>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-10 text-center text-sm text-white/60">{children}</p>
}

export function PageHeading({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <>
      <h1 className="mb-1.5 text-[25px] font-bold text-white">{title}</h1>
      {subtitle && <p className="mb-5 text-[13px] text-white/70">{subtitle}</p>}
    </>
  )
}
