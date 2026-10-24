import { http } from './http'
import type { Address, AddressPayload } from './types'

export const addressApi = {
  list(): Promise<Address[]> {
    return http.get<Address[]>('/addresses')
  },

  create(payload: AddressPayload): Promise<Address> {
    return http.post<Address>('/addresses', payload)
  },

  update(id: number, payload: AddressPayload): Promise<Address> {
    return http.put<Address>(`/addresses/${id}`, payload)
  },

  remove(id: number): Promise<void> {
    return http.delete<void>(`/addresses/${id}`)
  },

  setDefault(id: number): Promise<void> {
    return http.post<void>(`/addresses/${id}/default`)
  },
}
