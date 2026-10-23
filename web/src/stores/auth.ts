import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import { setAccessToken, setRefreshHandler } from '@/api/http'
import type { TokenResponse, UserView } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserView | null>(null)
  const ready = ref(false)
  const isAuthenticated = computed(() => user.value !== null)
  const isAdmin = computed(
    () => user.value?.role === 'ADMIN' || user.value?.role === 'SUPER_ADMIN',
  )
  const isSuperAdmin = computed(() => user.value?.role === 'SUPER_ADMIN')

  function applySession(session: TokenResponse): void {
    setAccessToken(session.accessToken)
    user.value = session.user
  }

  function clearSession(): void {
    setAccessToken(null)
    user.value = null
  }

  function setUser(next: UserView): void {
    user.value = next
  }

  async function refresh(): Promise<boolean> {
    try {
      applySession(await authApi.refresh())
      return true
    } catch {
      clearSession()
      return false
    }
  }

  setRefreshHandler(refresh)

  async function bootstrap(): Promise<void> {
    if (ready.value) return
    await refresh()
    ready.value = true
  }

  async function loginByCode(phone: string, code: string): Promise<void> {
    applySession(await authApi.loginByCode({ phone, code }))
  }

  async function loginByPassword(phone: string, password: string): Promise<void> {
    applySession(await authApi.loginByPassword({ phone, password }))
  }

  async function register(phone: string, code: string, password: string): Promise<void> {
    applySession(await authApi.register({ phone, code, password }))
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clearSession()
    }
  }

  return {
    user,
    ready,
    isAuthenticated,
    isAdmin,
    isSuperAdmin,
    bootstrap,
    loginByCode,
    loginByPassword,
    register,
    logout,
    setUser,
  }
})
