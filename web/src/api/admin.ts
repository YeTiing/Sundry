import { http } from './http'
import type { AdminUser, FileView, PageResponse, Role } from './types'

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
}
