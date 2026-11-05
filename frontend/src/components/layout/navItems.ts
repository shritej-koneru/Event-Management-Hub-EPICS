import { CalendarDays, LayoutDashboard, Map, Megaphone } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/events', label: 'Events', icon: CalendarDays },
  { to: '/map', label: 'Map', icon: Map },
  { to: '/announcements', label: 'Announcements', icon: Megaphone },
]
