import { ApiError, http } from './http'
import type { SmsScene, TokenResponse, UserView } from './types'

export const authApi = {
  sendCode(phone: string, scene: SmsScene): Promise<void> {
    return http.post<void>('/auth/sms-code', { phone, scene })
  },

  register(payload: { phone: string; code: string; password: string }): Promise<TokenResponse> {
    return http.post<TokenResponse>('/auth/register', payload)
  },

  loginByCode(payload: { phone: string; code: string }): Promise<TokenResponse> {
    return http.post<TokenResponse>('/auth/login/code', payload)
  },

  loginByPassword(payload: { phone: string; password: string }): Promise<TokenResponse> {
    return http.post<TokenResponse>('/auth/login/password', payload)
  },

  me(): Promise<UserView> {
    return http.get<UserView>('/auth/me')
  },

  logout(): Promise<void> {
    return http.post<void>('/auth/logout')
  },

  async refresh(): Promise<TokenResponse> {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
    })
    if (!response.ok) {
      throw new ApiError('INVALID_REFRESH_TOKEN', '登录已过期，请重新登录', response.status)
    }
    return (await response.json()) as TokenResponse
  },
}
