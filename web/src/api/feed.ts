import { http } from './http'
import type { Product, ScrollPage } from './types'

export const feedApi = {
  getFeed(cursor: number | null, size: number): Promise<ScrollPage<Product>> {
    const params = new URLSearchParams({ size: String(size) })
    if (cursor !== null) {
      params.set('cursor', String(cursor))
    }
    return http.get<ScrollPage<Product>>(`/feed?${params.toString()}`)
  },
}

export const behaviorApi = {
  recordView(productId: number): Promise<void> {
    return http.post<void>('/behaviors/view', { productId })
  },
}
