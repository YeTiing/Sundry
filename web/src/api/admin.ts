import { http } from './http'
import type {
  AdminUser,
  DigitalFile,
  DigitalUploadInit,
  FileView,
  PageResponse,
  Product,
  ProductStatus,
  ProductType,
  Role,
} from './types'

export interface SaveProductPayload {
  name: string
  description: string
  price: string
  categoryId: number
  coverFileId: number | null
  type: ProductType
  digitalFileId: number | null
  status?: ProductStatus
}

export const adminApi = {
  uploadImage(file: File): Promise<FileView> {
    const form = new FormData()
    form.append('file', file)
    return http.postForm<FileView>('/admin/files/images', form)
  },

  listUsers(page: number, size: number): Promise<PageResponse<AdminUser>> {
    return http.get<PageResponse<AdminUser>>(`/admin/users?page=${page}&size=${size}`)
  },

  updateRole(id: number, role: Role): Promise<AdminUser> {
    return http.patch<AdminUser>(`/admin/users/${id}/role`, { role })
  },

  listProducts(
    status: ProductStatus | null,
    page: number,
    size: number,
  ): Promise<PageResponse<Product>> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (status !== null) {
      params.set('status', status)
    }
    return http.get<PageResponse<Product>>(`/admin/products?${params.toString()}`)
  },

  getProduct(id: number): Promise<Product> {
    return http.get<Product>(`/admin/products/${id}`)
  },

  createProduct(payload: SaveProductPayload): Promise<Product> {
    return http.post<Product>('/admin/products', payload)
  },

  updateProduct(id: number, payload: SaveProductPayload): Promise<Product> {
    return http.put<Product>(`/admin/products/${id}`, payload)
  },

  changeProductStatus(id: number, status: ProductStatus): Promise<Product> {
    return http.patch<Product>(`/admin/products/${id}/status`, { status })
  },

  initDigitalUpload(payload: {
    fileName: string
    contentType: string
    sizeBytes: number
  }): Promise<DigitalUploadInit> {
    return http.post<DigitalUploadInit>('/admin/uploads/digital/init', payload)
  },

  completeDigitalUpload(payload: { uploadId: string; key: string }): Promise<DigitalFile> {
    return http.post<DigitalFile>('/admin/uploads/digital/complete', payload)
  },

  abortDigitalUpload(payload: { uploadId: string; key: string }): Promise<void> {
    return http.post<void>('/admin/uploads/digital/abort', payload)
  },
}
