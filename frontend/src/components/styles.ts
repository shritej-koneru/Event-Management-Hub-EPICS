/**
 * Shared class helpers for the "Campus ops" design system (docs/DESIGN_SYSTEM.md).
 * Colours, radii and type come from the @theme tokens in index.css; no raw values here.
 */

const buttonBase =
  'inline-flex h-9 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-control px-3.5 text-body font-medium transition-colors duration-150 ease-out disabled:cursor-not-allowed disabled:opacity-50 pointer-coarse:h-11'

/** Solid accent. One per view. */
export const buttonPrimary = `${buttonBase} border border-accent bg-accent text-on-accent hover:bg-accent-hover hover:border-accent-hover`

/** Surface + strong hairline. */
export const buttonSecondary = `${buttonBase} border border-line-strong bg-surface text-ink hover:bg-paper`

/** Text-only accent. */
export const buttonQuiet = `${buttonBase} border border-transparent bg-transparent px-2 text-accent hover:bg-accent-soft`

/** Secondary style with danger text. */
export const buttonDanger = `${buttonBase} border border-line-strong bg-surface text-danger hover:bg-danger-soft`

/** Destructive confirm. */
export const buttonDangerSolid = `${buttonBase} border border-danger bg-danger text-on-accent hover:opacity-90`

/** Square icon-only quiet button (44px touch target on touch devices). */
export const buttonIcon =
  'inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-control border border-transparent text-ink-2 transition-colors duration-150 ease-out hover:bg-surface-sunken hover:text-ink disabled:cursor-not-allowed disabled:opacity-50 pointer-coarse:h-11 pointer-coarse:w-11'

export const buttonIconDanger =
  'inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-control border border-transparent text-ink-2 transition-colors duration-150 ease-out hover:bg-danger-soft hover:text-danger disabled:cursor-not-allowed disabled:opacity-50 pointer-coarse:h-11 pointer-coarse:w-11'

/** Full-width variant helper, e.g. on the login panel. */
export const fullWidth = 'w-full'

export const inputClass =
  'h-9 w-full rounded-control border border-line-strong bg-surface px-3 text-body text-ink outline-none transition-colors duration-150 ease-out placeholder:text-ink-3 focus:border-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:bg-surface-sunken disabled:text-ink-3 pointer-coarse:h-11'

/** Multi-line input: same look, auto height. */
export const textareaClass = `${inputClass} h-auto min-h-20 py-2 pointer-coarse:h-auto`

/** Label wrapping its control: Small 500 ink-2 with the control spaced below. */
export const labelClass =
  'block text-small font-medium text-ink-2 [&>input]:mt-1 [&>select]:mt-1 [&>textarea]:mt-1'

export const helperClass = 'mt-1 block text-small font-normal text-ink-3'
export const errorTextClass = 'text-small text-danger'

/** Flat bordered container. */
export const panelClass = 'rounded-card border border-line bg-surface'
export const panelPadded = `${panelClass} p-4`
export const panelHeader = 'flex items-center justify-between gap-3 border-b border-line px-4 py-3'
export const panelTitle = 'text-heading font-semibold text-ink'

/** Table (inside a panel with `relative overflow-x-auto`: `relative` keeps sr-only/absolute children inside the scroll box, otherwise they widen the page). */
export const tableClass = 'w-full border-collapse text-left text-body'
export const theadClass = 'bg-surface-sunken'
export const thClass = 'px-4 py-2.5 text-overline font-semibold uppercase tracking-[0.06em] text-ink-2'
export const thRight = `${thClass} text-right`
export const trClass = 'group border-t border-line first:border-t-0 transition-colors duration-150 ease-out hover:bg-paper'
export const tdClass = 'h-11 px-4 py-2 align-middle'
export const tdRight = `${tdClass} text-right tabular-nums`

export const overlineClass = 'text-overline font-semibold uppercase tracking-[0.06em] text-ink-3'
export const captionClass = 'text-caption font-medium text-ink-3'

export type ChipTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'

const chipTones: Record<ChipTone, string> = {
  neutral: 'bg-surface-sunken text-ink-2',
  accent: 'bg-accent-soft text-accent',
  success: 'bg-success-soft text-success',
  warning: 'bg-warning-soft text-warning',
  danger: 'bg-danger-soft text-danger',
  info: 'bg-info-soft text-info',
}

/** Status chip: 20px, soft background, strong text of the same colour. */
export const chipClass = (tone: ChipTone = 'neutral') =>
  `inline-flex h-5 items-center gap-1 whitespace-nowrap rounded-control px-2 text-caption font-medium ${chipTones[tone]}`

/** Count badge (nav, lists). */
export const badgeClass =
  'inline-flex h-5 min-w-5 items-center justify-center rounded-control bg-accent px-1.5 text-caption font-semibold tabular-nums text-on-accent'

/** Overlays: menus, dialogs, sheets. */
export const overlayClass = 'rounded-card border border-line bg-surface shadow-overlay'

/** Inline notices. */
export const noticeClass = (tone: 'success' | 'danger' | 'info') =>
  `rounded-control border px-3 py-2 text-small ${
    tone === 'success'
      ? 'border-success/30 bg-success-soft text-success'
      : tone === 'danger'
        ? 'border-danger/30 bg-danger-soft text-danger'
        : 'border-info/30 bg-info-soft text-info'
  }`

/** In-text / header link in the accent colour. */
export const linkClass =
  'rounded-control text-small font-medium text-accent hover:text-accent-hover hover:underline'
