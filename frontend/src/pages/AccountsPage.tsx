import { useState } from 'react'
import type { FormEvent } from 'react'
import { UserPlus } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import { Empty, ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import { cardClass, compactPrimaryClass, inputClass } from '@/components/styles'
import { api, ApiError } from '@/lib/api'
import { apiErrorMessage } from '@/lib/manage'
import type { ManagedUser, Role } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'

const ROLES: Role[] = ['organizer', 'admin', 'student']
const label = 'block text-xs font-semibold'

function accountsError(err: unknown): string {
  if (err instanceof ApiError && err.status === 403) return 'Only admins can manage accounts.'
  return apiErrorMessage(err)
}

export default function AccountsPage() {
  const { user: me } = useAuth()
  const users = useFetch((signal) => api<{ users: ManagedUser[] }>('/manage/users', { signal }))
  const [form, setForm] = useState({ name: '', email: '', password: '', role: 'organizer' as Role })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [rowBusy, setRowBusy] = useState<string | null>(null)
  const [rowError, setRowError] = useState<string | null>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    if (!form.name.trim()) return setError('Name is required.')
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) return setError('Enter a valid email.')
    if (form.password.length < 8) return setError('Password must be at least 8 characters.')
    setBusy(true)
    try {
      await api('/manage/users', {
        method: 'POST',
        body: { name: form.name.trim(), email: form.email.trim(), password: form.password, role: form.role },
      })
      setNotice(`Created account for ${form.name.trim()}.`)
      setForm({ name: '', email: '', password: '', role: form.role })
      users.reload()
    } catch (err) {
      setError(accountsError(err))
    } finally {
      setBusy(false)
    }
  }

  async function changeRole(u: ManagedUser, role: Role) {
    if (role === u.role) return
    setRowBusy(u.id)
    setRowError(null)
    setNotice(null)
    try {
      await api(`/manage/users/${encodeURIComponent(u.id)}`, { method: 'PUT', body: { role } })
      setNotice(`${u.name} is now ${role}.`)
      users.reload()
    } catch (err) {
      setRowError(accountsError(err))
    } finally {
      setRowBusy(null)
    }
  }

  const list = users.data?.users ?? []

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Accounts" subtitle="Create accounts and change roles (admins only)" />

      {notice && (
        <p role="status" className="mb-3 rounded-[14px] bg-green-500/25 px-4 py-3 text-sm text-white">
          {notice}
        </p>
      )}

      <section aria-labelledby="create-account" className={cardClass}>
        <h2 id="create-account" className="mb-3 text-lg font-bold text-[#5c3ce8]">
          Create account
        </h2>
        <form onSubmit={create} aria-label="Create account" className="grid gap-3 md:grid-cols-2">
          <label className={label}>
            Name
            <input
              required
              className={inputClass}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          </label>
          <label className={label}>
            Email
            <input
              required
              type="email"
              autoComplete="off"
              className={inputClass}
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
            />
          </label>
          <label className={label}>
            Password (min 8 characters)
            <input
              required
              type="password"
              minLength={8}
              autoComplete="new-password"
              className={inputClass}
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
            />
          </label>
          <label className={label}>
            Role
            <select
              className={inputClass}
              value={form.role}
              onChange={(e) => setForm({ ...form, role: e.target.value as Role })}
            >
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </label>
          {error && (
            <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 md:col-span-2">
              {error}
            </p>
          )}
          <div className="md:col-span-2">
            <button type="submit" disabled={busy} className={compactPrimaryClass}>
              <UserPlus className="h-4 w-4" aria-hidden />
              Create account
            </button>
          </div>
        </form>
      </section>

      <section aria-labelledby="user-list" className={`${cardClass} mt-5`}>
        <h2 id="user-list" className="mb-2 text-lg font-bold text-[#5c3ce8]">
          Users ({list.length})
        </h2>
        {rowError && (
          <p role="alert" className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {rowError}
          </p>
        )}
        {users.error && <ErrorNote>{users.error}</ErrorNote>}
        {users.loading && <Loading />}
        {!users.loading && !users.error && list.length === 0 && <Empty>No accounts.</Empty>}
        {list.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="text-xs uppercase text-[#77758b]">
                  <th scope="col" className="py-2 pr-3">Name</th>
                  <th scope="col" className="py-2 pr-3">Email</th>
                  <th scope="col" className="py-2 pr-3">Role</th>
                  <th scope="col" className="py-2">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#eee9fb]">
                {list.map((u) => (
                  <tr key={u.id}>
                    <td className="py-2 pr-3">
                      {u.name}
                      {u.id === me?.id && <span className="ml-1 text-xs text-[#77758b]">(you)</span>}
                    </td>
                    <td className="py-2 pr-3 break-all">{u.email}</td>
                    <td className="py-2 pr-3">
                      <select
                        aria-label={`Role for ${u.name}`}
                        className="h-9 rounded-[11px] border border-[#ddd8ef] bg-white px-2 text-sm"
                        value={u.role}
                        disabled={rowBusy === u.id}
                        onChange={(e) => void changeRole(u, e.target.value as Role)}
                      >
                        {ROLES.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-2 whitespace-nowrap">{new Date(u.createdAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
