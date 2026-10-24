import { http } from './http'
import type { CartItem } from './types'

export const cartApi = {
  list(): Promise<CartItem[]> {
    return http.get<CartItem[]>('/cart')
  },

  add(productId: number, quantity: number): Promise<CartItem> {
    return http.post<CartItem>('/cart', { productId, quantity })
  },

  update(id: number, quantity: number): Promise<CartItem> {
    return http.put<CartItem>(`/cart/${id}`, { quantity })
  },

  remove(id: number): Promise<void> {
    return http.delete<void>(`/cart/${id}`)
  },
}
