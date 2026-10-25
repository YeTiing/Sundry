import { http } from './http'
import type {
  AdminCategory,
  AdminUser,
  DigitalFile,
  DownloadInfo,
  DigitalUploadInit,
  FileView,
  PageResponse,
  Product,
  ProductStatus,
  ProductType,
  ReusableDigitalFile,
  ReusableImage,
  Role,
} from './types'

export interface SaveCategoryPayload {
  name: string
  sortOrder: number
  status?: number
}

export interface SaveProductPayload {
  name: string
  description: string
  price: string
  stock: number | null
  categoryId: number
  coverFileId: number | null
  type: ProductType
  digitalFileId: number | null
  status?: ProductStatus
  tags: string[]
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

  downloadProduct(id: number): Promise<DownloadInfo> {
    return http.get<DownloadInfo>(`/admin/products/${id}/download`)
  },

  createProduct(payload: SaveProductPayload, idempotencyKey?: string): Promise<Product> {
    return http.post<Product>(
      '/admin/products',
      payload,
      idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    )
  },

  updateProduct(id: number, payload: SaveProductPayload): Promise<Product> {
    return http.put<Product>(`/admin/products/${id}`, payload)
  },

  changeProductStatus(id: number, status: ProductStatus): Promise<Product> {
    return http.patch<Product>(`/admin/products/${id}/status`, { status })
  },

  deleteProduct(id: number): Promise<void> {
    return http.delete<void>(`/admin/products/${id}`)
  },

  listDeletedProducts(page: number, size: number): Promise<PageResponse<Product>> {
    return http.get<PageResponse<Product>>(`/admin/products/deleted?page=${page}&size=${size}`)
  },

  restoreProduct(id: number): Promise<Product> {
    return http.post<Product>(`/admin/products/${id}/restore`)
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

  deleteFile(id: number): Promise<void> {
    return http.delete<void>(`/admin/files/${id}`)
  },

  listReusableImages(): Promise<ReusableImage[]> {
    return http.get<ReusableImage[]>('/admin/files/images/reusable')
  },

  reuseImage(id: number): Promise<ReusableImage> {
    return http.post<ReusableImage>(`/admin/files/images/${id}/reuse`)
  },

  listReusableDigitalFiles(): Promise<ReusableDigitalFile[]> {
    return http.get<ReusableDigitalFile[]>('/admin/uploads/digital/reusable')
  },

  reuseDigitalFile(id: number): Promise<DigitalFile> {
    return http.post<DigitalFile>(`/admin/uploads/digital/${id}/reuse`)
  },

  listCategories(): Promise<AdminCategory[]> {
    return http.get<AdminCategory[]>('/admin/categories')
  },

  createCategory(payload: SaveCategoryPayload): Promise<AdminCategory> {
    return http.post<AdminCategory>('/admin/categories', payload)
  },

  updateCategory(id: number, payload: SaveCategoryPayload): Promise<AdminCategory> {
    return http.put<AdminCategory>(`/admin/categories/${id}`, payload)
  },

  deleteCategory(id: number): Promise<void> {
    return http.delete<void>(`/admin/categories/${id}`)
  },
}
