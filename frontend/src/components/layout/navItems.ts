import { CalendarDays, LayoutDashboard, Map, Megaphone, Users } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Only shown to admins. */
  adminOnly?: boolean
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/events', label: 'Events', icon: CalendarDays },
  { to: '/map', label: 'Map', icon: Map },
  { to: '/announcements', label: 'Announcements', icon: Megaphone },
  { to: '/accounts', label: 'Accounts', icon: Users, adminOnly: true },
]

export const navItemsFor = (role: string | undefined): NavItem[] =>
  NAV_ITEMS.filter((i) => !i.adminOnly || role === 'admin')
