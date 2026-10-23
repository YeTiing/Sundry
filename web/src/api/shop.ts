import { http } from './http'
import type { ShopView } from './types'

export interface UpdateShopPayload {
  shopName: string
  description: string
  contact: string
  logoFileId: number | null
}

export const shopApi = {
  get(): Promise<ShopView> {
    return http.get<ShopView>('/shop')
  },

  adminGet(): Promise<ShopView> {
    return http.get<ShopView>('/admin/shop')
  },

  update(payload: UpdateShopPayload): Promise<ShopView> {
    return http.put<ShopView>('/admin/shop', payload)
  },
}
