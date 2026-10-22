export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'USER'

export interface UserView {
  id: number
  phone: string
  role: Role
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

export interface AdminUser {
  id: number
  phone: string
  role: Role
  active: boolean
  createdAt: string
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface FileView {
  id: number
  key: string
  url: string
  originalName: string
  size: number
  contentType: string
}
