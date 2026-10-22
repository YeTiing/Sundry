export interface UserView {
  id: number
  phone: string
}

export interface TokenResponse {
  accessToken: string
  expiresIn: number
  user: UserView
}

export interface ApiErrorBody {
  code: string
  message: string
}

export type SmsScene = 'REGISTER' | 'LOGIN'
