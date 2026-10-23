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

export type ProductStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SHELF'
export type ProductType = 'PHYSICAL' | 'DIGITAL'

export interface Category {
  id: number
  name: string
  sortOrder: number
}

export interface Product {
  id: number
  name: string
  description: string
  type: ProductType
  price: number
  categoryId: number
  categoryName: string
  coverFileId: number | null
  coverUrl: string | null
  digitalFileId: number | null
  status: ProductStatus
  deletedAt: string | null
  createdAt: string
}

export interface DigitalPart {
  partNumber: number
  url: string
}

export interface DigitalUploadInit {
  uploadId: string
  key: string
  partSize: number
  partCount: number
  parts: DigitalPart[]
}

export interface DigitalFile {
  id: number
  key: string
  originalName: string
  sizeBytes: number
  contentType: string
}

export interface ReusableImage {
  id: number
  originalName: string
  sizeBytes: number
  status: number
  deletedAt: string | null
  url: string
}

export interface ReusableDigitalFile {
  id: number
  originalName: string
  sizeBytes: number
  contentType: string
  status: number
  deletedAt: string | null
  createdAt: string
}
