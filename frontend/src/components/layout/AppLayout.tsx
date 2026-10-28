import { Outlet } from 'react-router-dom'

import BottomNav from './BottomNav'
import Navbar from './Navbar'
import Sidebar from './Sidebar'

export default function AppLayout() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-[#24106f] via-[#5420d8] to-[#7b35ed] lg:bg-gradient-to-br lg:from-[#21105d] lg:via-[#5c28df] lg:to-[#8a43ed]">
      <Navbar />
      <Sidebar />
      <main className="max-lg:pb-[110px] lg:ml-64">
        <div className="relative mx-auto min-h-screen w-full max-w-[480px] overflow-hidden bg-gradient-to-br from-[#3510ad] via-[#5620dc] to-[#7430e8] shadow-[0_0_50px_rgba(0,0,0,0.35)]">
          <Outlet />
        </div>
      </main>
      <BottomNav />
    </div>
  )
}
