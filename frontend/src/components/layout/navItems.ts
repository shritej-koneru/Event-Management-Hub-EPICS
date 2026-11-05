import { CalendarDays, LayoutDashboard, LifeBuoy, Map, Megaphone, Users } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  /** Short label for the mobile bottom bar. */
  short: string
  icon: LucideIcon
  /** Only shown to admins. */
  adminOnly?: boolean
  /** Show the open help-desk chat count. */
  badge?: boolean
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', short: 'Home', icon: LayoutDashboard },
  { to: '/events', label: 'Events', short: 'Events', icon: CalendarDays },
  { to: '/map', label: 'Map', short: 'Map', icon: Map },
  { to: '/announcements', label: 'Announcements', short: 'Notices', icon: Megaphone },
  { to: '/help-desk', label: 'Help desk', short: 'Help', icon: LifeBuoy, badge: true },
  { to: '/accounts', label: 'Accounts', short: 'Accounts', icon: Users, adminOnly: true },
]

export const navItemsFor = (role: string | undefined): NavItem[] =>
  NAV_ITEMS.filter((i) => !i.adminOnly || role === 'admin')
