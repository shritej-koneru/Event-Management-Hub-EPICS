import { Navigate, Outlet } from 'react-router-dom'

import { NOT_MANAGER_MESSAGE } from '@/auth/context'
import { useAuth } from '@/auth/useAuth'
import { Loading } from '@/components/Feedback'

import BottomNav from './BottomNav'
import Navbar from './Navbar'
import Sidebar from './Sidebar'

/** Console shell. Only organizer/admin sessions get past this point. */
export default function AppLayout() {
  const { user, loading, isManager, logout } = useAuth()

  if (loading) {
    return (
      <div className="min-h-screen bg-[#3510ad]">
        <Loading label="Checking your session..." />
      </div>
    )
  }
  if (!user) return <Navigate to="/login" replace />
  if (!isManager) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-[#3510ad] px-6 text-center text-white">
        <p role="alert" className="text-lg font-bold">
          {NOT_MANAGER_MESSAGE}
        </p>
        <button
          type="button"
          onClick={logout}
          className="h-10 cursor-pointer rounded-[13px] bg-white px-5 text-sm font-bold text-[#5c3ce8]"
        >
          Sign out
        </button>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-[#24106f] via-[#5420d8] to-[#7b35ed] lg:bg-gradient-to-br lg:from-[#21105d] lg:via-[#5c28df] lg:to-[#8a43ed]">
      <Navbar />
      <Sidebar />
      <main className="pt-16 max-lg:pb-[110px] lg:ml-64">
        <div className="relative mx-auto min-h-screen w-full max-w-6xl">
          <Outlet />
        </div>
      </main>
      <BottomNav />
    </div>
  )
}
