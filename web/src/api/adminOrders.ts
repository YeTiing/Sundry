import { http } from './http'
import type { Order, OrderStatus, PageResponse } from './types'

export const adminOrdersApi = {
  list(status: OrderStatus | null, page: number, size: number): Promise<PageResponse<Order>> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (status !== null) {
      params.set('status', status)
    }
    return http.get<PageResponse<Order>>(`/admin/orders?${params.toString()}`)
  },

  get(orderNo: string): Promise<Order> {
    return http.get<Order>(`/admin/orders/${orderNo}`)
  },

  pack(orderNo: string): Promise<Order> {
    return http.post<Order>(`/admin/orders/${orderNo}/pack`)
  },

  ship(orderNo: string, trackingNo: string): Promise<Order> {
    return http.post<Order>(`/admin/orders/${orderNo}/ship`, { trackingNo })
  },

  approveRefund(orderNo: string, remark: string | null): Promise<Order> {
    return http.post<Order>(
      `/admin/orders/${orderNo}/refund/approve`,
      remark ? { remark } : undefined,
    )
  },

  rejectRefund(orderNo: string, reason: string): Promise<Order> {
    return http.post<Order>(`/admin/orders/${orderNo}/refund/reject`, { reason })
  },
}
