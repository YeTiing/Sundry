import { http } from './http'
import type { Category, PageResponse, Product } from './types'

/**
 * 商城公开接口，不需要登录。
 */
export const catalogApi = {
  categories(): Promise<Category[]> {
    return http.get<Category[]>('/categories')
  },

  products(categoryId: number | null, page: number, size: number): Promise<PageResponse<Product>> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (categoryId !== null) {
      params.set('categoryId', String(categoryId))
    }
    return http.get<PageResponse<Product>>(`/products?${params.toString()}`)
  },

  product(id: number): Promise<Product> {
    return http.get<Product>(`/products/${id}`)
  },
}
