import { http } from './http'
import type { DownloadInfo, Order, PageResponse } from './types'

export const ordersApi = {
  create(cartItemIds: number[], addressId: number | null, idempotencyKey: string): Promise<Order> {
    return http.post<Order>(
      '/orders',
      { cartItemIds, addressId },
      { 'Idempotency-Key': idempotencyKey },
    )
  },

  list(page: number, size: number): Promise<PageResponse<Order>> {
    return http.get<PageResponse<Order>>(`/orders?page=${page}&size=${size}`)
  },

  get(orderNo: string): Promise<Order> {
    return http.get<Order>(`/orders/${orderNo}`)
  },

  pay(orderNo: string): Promise<Order> {
    return http.post<Order>(`/orders/${orderNo}/pay`)
  },

  cancel(orderNo: string): Promise<Order> {
    return http.post<Order>(`/orders/${orderNo}/cancel`)
  },

  confirm(orderNo: string): Promise<Order> {
    return http.post<Order>(`/orders/${orderNo}/confirm`)
  },

  download(orderNo: string, itemId: number): Promise<DownloadInfo> {
    return http.get<DownloadInfo>(`/orders/${orderNo}/items/${itemId}/download`)
  },

  refund(orderNo: string, reason: string): Promise<Order> {
    return http.post<Order>(`/orders/${orderNo}/refund`, { reason })
  },

  withdrawRefund(orderNo: string): Promise<Order> {
    return http.post<Order>(`/orders/${orderNo}/refund/withdraw`)
  },
}
