<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import type { AdminUser, FileView, Role } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

interface AdminUserRow extends AdminUser {
  pendingRole: Role
}

const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const PAGE_SIZE = 10

const auth = useAuthStore()
const router = useRouter()

const file = ref<File | null>(null)
const previewUrl = ref('')
const uploading = ref(false)
const uploadError = ref('')
const result = ref<FileView | null>(null)
const copied = ref(false)

const users = ref<AdminUserRow[]>([])
const page = ref(1)
const total = ref(0)
const loadingUsers = ref(false)
const userError = ref('')
const savingId = ref<number | null>(null)
const notice = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function roleLabel(role: Role): string {
  return role === 'SUPER_ADMIN' ? '超级管理员' : role === 'ADMIN' ? '管理员' : '普通用户'
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function applyFile(selected: File | null): void {
  uploadError.value = ''
  result.value = null
  copied.value = false

  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  file.value = null

  if (!selected) return
  if (!ALLOWED_TYPES.includes(selected.type)) {
    uploadError.value = '只支持 JPEG、PNG、WebP 图片'
    return
  }
  if (selected.size > MAX_IMAGE_BYTES) {
    uploadError.value = '图片不能超过 5MB'
    return
  }

  file.value = selected
  previewUrl.value = URL.createObjectURL(selected)
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  applyFile(input.files?.[0] ?? null)
  if (!file.value) input.value = ''
}

function onDrop(event: DragEvent): void {
  applyFile(event.dataTransfer?.files?.[0] ?? null)
}

async function upload(): Promise<void> {
  if (!file.value || uploading.value) return
  uploading.value = true
  uploadError.value = ''
  try {
    result.value = await adminApi.uploadImage(file.value)
  } catch (error) {
    uploadError.value = messageOf(error)
  } finally {
    uploading.value = false
  }
}

async function copyUrl(): Promise<void> {
  if (!result.value) return
  try {
    await navigator.clipboard.writeText(result.value.url)
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    uploadError.value = '复制失败，请手动选中链接复制'
  }
}

async function loadUsers(target: number): Promise<void> {
  loadingUsers.value = true
  userError.value = ''
  notice.value = ''
  try {
    const data = await adminApi.listUsers(target, PAGE_SIZE)
    users.value = data.items.map((user) => ({ ...user, pendingRole: user.role }))
    total.value = data.total
    page.value = data.page
  } catch (error) {
    userError.value = messageOf(error)
  } finally {
    loadingUsers.value = false
  }
}

async function saveRole(user: AdminUserRow): Promise<void> {
  if (savingId.value !== null || user.pendingRole === user.role) return
  savingId.value = user.id
  userError.value = ''
  notice.value = ''
  try {
    const updated = await adminApi.updateRole(user.id, user.pendingRole)
    user.role = updated.role
    user.pendingRole = updated.role
    notice.value = `${updated.phone} 已改为${roleLabel(updated.role)}`
  } catch (error) {
    user.pendingRole = user.role
    userError.value = messageOf(error)
  } finally {
    savingId.value = null
  }
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(() => {
  if (auth.isSuperAdmin) {
    void loadUsers(1)
  }
})

onBeforeUnmount(() => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
  }
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">商品图上传</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin/products">商品管理</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <section class="admin-panel">
          <h2>上传图片</h2>
          <p class="panel-note">
            JPEG / PNG / WebP，单张不超过 5MB。文件会存入公开桶，返回可直接使用的图片链接。
          </p>

          <label class="dropzone" @dragover.prevent @drop.prevent="onDrop">
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              :disabled="uploading"
              @change="onFileChange"
            />
            <span class="dropzone__title">选择一张商品图</span>
            <span class="dropzone__hint">点击选择，或把文件拖到这里</span>
          </label>

          <div v-if="previewUrl" class="preview">
            <img :src="previewUrl" alt="待上传图片预览" />
          </div>

          <p v-if="uploadError" class="form-error">{{ uploadError }}</p>

          <div class="admin-actions">
            <button
              class="primary-button admin-action"
              type="button"
              :disabled="!file || uploading"
              @click="upload"
            >
              {{ uploading ? '上传中…' : '上传' }}
            </button>
          </div>

          <div v-if="result" class="result">
            <img :src="result.url" :alt="result.originalName" />
            <code>{{ result.url }}</code>
            <div class="admin-actions">
              <button class="secondary-button" type="button" @click="copyUrl">
                {{ copied ? '已复制' : '复制链接' }}
              </button>
              <span class="panel-note result__meta">
                {{ result.originalName }} · {{ Math.round(result.size / 1024) }} KB
              </span>
            </div>
          </div>
        </section>

        <section v-if="auth.isSuperAdmin" class="admin-panel">
          <h2>用户与角色</h2>
          <p class="panel-note">
            只有你能调整角色。提升为管理员后，对方立即获得商品管理权限，不用重新登录。
          </p>

          <p v-if="userError" class="form-error">{{ userError }}</p>
          <p v-if="notice" class="notice">{{ notice }}</p>

          <table class="admin-table">
            <thead>
              <tr>
                <th>手机号</th>
                <th>当前角色</th>
                <th>调整</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td class="mono">{{ user.phone }}</td>
                <td><span class="role-tag">{{ roleLabel(user.role) }}</span></td>
                <td>
                  <template v-if="user.role === 'SUPER_ADMIN'">
                    <span class="panel-note">不可修改</span>
                  </template>
                  <template v-else>
                    <div class="row-actions">
                      <select v-model="user.pendingRole" :disabled="savingId === user.id">
                        <option value="USER">普通用户</option>
                        <option value="ADMIN">管理员</option>
                      </select>
                      <button
                        class="secondary-button"
                        type="button"
                        :disabled="
                          savingId === user.id || user.pendingRole === user.role || loadingUsers
                        "
                        @click="saveRole(user)"
                      >
                        {{ savingId === user.id ? '保存中…' : '保存' }}
                      </button>
                    </div>
                  </template>
                </td>
              </tr>
              <tr v-if="!loadingUsers && users.length === 0">
                <td colspan="3" class="panel-note">还没有用户</td>
              </tr>
            </tbody>
          </table>

          <div class="pager">
            <button
              class="secondary-button"
              type="button"
              :disabled="loadingUsers || page <= 1"
              @click="loadUsers(page - 1)"
            >
              上一页
            </button>
            <span>{{ page }} / {{ totalPages }} · 共 {{ total }} 人</span>
            <button
              class="secondary-button"
              type="button"
              :disabled="loadingUsers || page >= totalPages"
              @click="loadUsers(page + 1)"
            >
              下一页
            </button>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>
