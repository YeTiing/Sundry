<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { shopApi } from '@/api/shop'
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
const deleting = ref(false)

const users = ref<AdminUserRow[]>([])
const page = ref(1)
const total = ref(0)
const loadingUsers = ref(false)
const userError = ref('')
const savingId = ref<number | null>(null)
const notice = ref('')

const shopName = ref('')
const shopDescription = ref('')
const shopContact = ref('')
const shopShippingAddress = ref('')
const shopLogoFileId = ref<number | null>(null)
const shopLogoUrl = ref('')
const shopSaving = ref(false)
const uploadingShopLogo = ref(false)
const shopError = ref('')
const shopNotice = ref('')

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

async function deleteUploadedImage(): Promise<void> {
  if (!result.value || deleting.value) return
  if (!window.confirm('删除这张图片？删除后 1 天内仍可被复用，之后自动清理。')) return

  deleting.value = true
  uploadError.value = ''
  try {
    await adminApi.deleteFile(result.value.id)
    result.value = null
    copied.value = false
    file.value = null
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
      previewUrl.value = ''
    }
  } catch (error) {
    uploadError.value = messageOf(error)
  } finally {
    deleting.value = false
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

async function loadShop(): Promise<void> {
  shopError.value = ''
  try {
    const shop = await shopApi.adminGet()
    shopName.value = shop.shopName
    shopDescription.value = shop.description ?? ''
    shopContact.value = shop.contact ?? ''
    shopShippingAddress.value = shop.shippingAddress ?? ''
    shopLogoFileId.value = shop.logoFileId
    shopLogoUrl.value = shop.logoUrl ?? ''
  } catch (error) {
    shopError.value = messageOf(error)
  }
}

async function onShopLogoChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  if (!selected) return

  uploadingShopLogo.value = true
  shopError.value = ''
  try {
    const uploaded = await adminApi.uploadImage(selected)
    shopLogoFileId.value = uploaded.id
    shopLogoUrl.value = uploaded.url
  } catch (error) {
    shopError.value = messageOf(error)
  } finally {
    uploadingShopLogo.value = false
    input.value = ''
  }
}

function clearShopLogo(): void {
  shopLogoFileId.value = null
  shopLogoUrl.value = ''
}

async function saveShop(): Promise<void> {
  if (shopSaving.value) return
  if (!shopName.value.trim()) {
    shopError.value = '店名不能为空'
    return
  }

  shopSaving.value = true
  shopError.value = ''
  shopNotice.value = ''
  try {
    const updated = await shopApi.update({
      shopName: shopName.value.trim(),
      description: shopDescription.value.trim(),
      contact: shopContact.value.trim(),
      shippingAddress: shopShippingAddress.value.trim(),
      logoFileId: shopLogoFileId.value,
    })
    shopName.value = updated.shopName
    shopDescription.value = updated.description ?? ''
    shopContact.value = updated.contact ?? ''
    shopShippingAddress.value = updated.shippingAddress ?? ''
    shopLogoFileId.value = updated.logoFileId
    shopLogoUrl.value = updated.logoUrl ?? ''
    shopNotice.value = '店铺资料已保存'
  } catch (error) {
    shopError.value = messageOf(error)
  } finally {
    shopSaving.value = false
  }
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(() => {
  if (auth.isSuperAdmin) {
    void loadUsers(1)
    void loadShop()
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
          <RouterLink to="/admin/categories">分类管理</RouterLink>
          <RouterLink to="/admin/orders">订单管理</RouterLink>
          <RouterLink to="/admin/seckill">秒杀活动</RouterLink>
          <RouterLink to="/admin/feed">推荐指标</RouterLink>
          <RouterLink to="/admin/nl2sql">经营分析</RouterLink>
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
              <button
                class="secondary-button danger-button"
                type="button"
                :disabled="deleting"
                @click="deleteUploadedImage"
              >
                {{ deleting ? '删除中…' : '删除图片' }}
              </button>
              <span class="panel-note result__meta">
                {{ result.originalName }} · {{ Math.round(result.size / 1024) }} KB
              </span>
            </div>
          </div>
        </section>

        <section v-if="auth.isSuperAdmin" class="admin-panel">
          <h2>店铺资料</h2>
          <p class="panel-note">商品详情页会展示这张店铺名片；只有超级管理员能修改。</p>

          <div class="shop-admin">
            <div class="shop-admin__logo">
              <img v-if="shopLogoUrl" :src="shopLogoUrl" alt="店铺 Logo" />
              <span v-else class="product-card__placeholder">Logo</span>
              <label class="secondary-button upload-label">
                {{ uploadingShopLogo ? '上传中…' : '上传 Logo' }}
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  :disabled="uploadingShopLogo"
                  @change="onShopLogoChange"
                />
              </label>
              <button
                v-if="shopLogoUrl"
                class="secondary-button"
                type="button"
                @click="clearShopLogo"
              >
                移除 Logo
              </button>
            </div>

            <div class="shop-admin__fields admin-form__grid">
              <label class="field">
                <span class="field__label">店名</span>
                <input v-model.trim="shopName" type="text" maxlength="100" placeholder="店铺名称" />
              </label>
              <label class="field">
                <span class="field__label">联系方式</span>
                <input
                  v-model.trim="shopContact"
                  type="text"
                  maxlength="200"
                  placeholder="微信 / 邮箱 / 电话"
                />
              </label>
            </div>
          </div>

          <label class="field">
            <span class="field__label">店铺简介</span>
            <textarea
              v-model.trim="shopDescription"
              rows="3"
              maxlength="500"
              placeholder="店铺介绍"
            />
          </label>

          <label class="field">
            <span class="field__label">发货地址</span>
            <textarea
              v-model.trim="shopShippingAddress"
              rows="2"
              maxlength="300"
              placeholder="商家寄件/退货地址，发货时在订单页显示"
            />
          </label>

          <p v-if="shopError" class="form-error">{{ shopError }}</p>
          <p v-if="shopNotice" class="notice">{{ shopNotice }}</p>

          <div class="admin-actions">
            <button
              class="primary-button admin-action"
              type="button"
              :disabled="shopSaving"
              @click="saveShop"
            >
              {{ shopSaving ? '保存中…' : '保存店铺资料' }}
            </button>
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
