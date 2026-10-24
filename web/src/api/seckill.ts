import { http } from './http'
import type { Order, PageResponse, SeckillActivity, SeckillStatus } from './types'

export interface SaveSeckillPayload {
  productId: number
  seckillPrice: string
  stock: number
  perUserLimit: number
  startAt: string
  endAt: string
}

export const seckillApi = {
  /** 商品详情用；没有进行中的活动时后端返回 404。 */
  productActivity(productId: number): Promise<SeckillActivity> {
    return http.get<SeckillActivity>(`/seckill/products/${productId}`)
  },

  activity(id: number): Promise<SeckillActivity> {
    return http.get<SeckillActivity>(`/seckill/activities/${id}`)
  },

  createOrder(activityId: number, addressId: number | null): Promise<Order> {
    return http.post<Order>('/seckill/orders', { activityId, addressId })
  },
}

export const adminSeckillApi = {
  list(status: SeckillStatus | null, page: number, size: number): Promise<PageResponse<SeckillActivity>> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (status !== null) {
      params.set('status', status)
    }
    return http.get<PageResponse<SeckillActivity>>(`/admin/seckill/activities?${params.toString()}`)
  },

  create(payload: SaveSeckillPayload): Promise<SeckillActivity> {
    return http.post<SeckillActivity>('/admin/seckill/activities', payload)
  },

  update(id: number, payload: SaveSeckillPayload): Promise<SeckillActivity> {
    return http.put<SeckillActivity>(`/admin/seckill/activities/${id}`, payload)
  },

  activate(id: number): Promise<SeckillActivity> {
    return http.post<SeckillActivity>(`/admin/seckill/activities/${id}/activate`)
  },

  cancel(id: number): Promise<SeckillActivity> {
    return http.post<SeckillActivity>(`/admin/seckill/activities/${id}/cancel`)
  },

  end(id: number): Promise<SeckillActivity> {
    return http.post<SeckillActivity>(`/admin/seckill/activities/${id}/end`)
  },
}
