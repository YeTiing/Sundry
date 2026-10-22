<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, type SaveProductPayload } from '@/api/admin'
import { catalogApi } from '@/api/catalog'
import type { Category, Product, ProductStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const PAGE_SIZE = 10
const PRICE_PATTERN = /^\d{1,8}(\.\d{1,2})?$/

const auth = useAuthStore()
const router = useRouter()

const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const page = ref(1)
const total = ref(0)
const statusFilter = ref<ProductStatus | null>(null)
const listLoading = ref(false)
const listError = ref('')
const notice = ref('')

const editingId = ref<number | null>(null)
const name = ref('')
const description = ref('')
const priceInput = ref('')
const categoryId = ref<number | null>(null)
const coverFileId = ref<number | null>(null)
const coverUrl = ref('')
const createStatus = ref<ProductStatus>('DRAFT')
const uploadingCover = ref(false)
const saving = ref(false)
const formError = ref('')

const isEditing = computed(() => editingId.value !== null)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function statusLabel(status: ProductStatus): string {
  return status === 'ON_SALE' ? '已上架' : status === 'OFF_SHELF' ? '已下架' : '草稿'
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await catalogApi.categories()
  } catch (err) {
    listError.value = messageOf(err)
  }
}

async function loadProducts(target = page.value): Promise<void> {
  listLoading.value = true
  listError.value = ''
  try {
    const data = await adminApi.listProducts(statusFilter.value, target, PAGE_SIZE)
    products.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (err) {
    listError.value = messageOf(err)
  } finally {
    listLoading.value = false
  }
}

function resetForm(): void {
  editingId.value = null
  name.value = ''
  description.value = ''
  priceInput.value = ''
  categoryId.value = categories.value[0]?.id ?? null
  coverFileId.value = null
  coverUrl.value = ''
  createStatus.value = 'DRAFT'
  formError.value = ''
}

function startCreate(): void {
  resetForm()
  notice.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function startEdit(product: Product): void {
  editingId.value = product.id
  name.value = product.name
  description.value = product.description
  priceInput.value = Number(product.price).toFixed(2)
  categoryId.value = product.categoryId
  coverFileId.value = product.coverFileId
  coverUrl.value = product.coverUrl ?? ''
  formError.value = ''
  notice.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function validateForm(): string {
  if (!name.value.trim()) return '商品名称不能为空'
  if (!description.value.trim()) return '商品描述不能为空'
  if (!PRICE_PATTERN.test(priceInput.value.trim())) {
    return '价格格式不正确，最多 8 位整数和 2 位小数'
  }
  if (categoryId.value === null) return '请选择分类'
  return ''
}

async function save(): Promise<void> {
  formError.value = validateForm()
  if (formError.value) return

  saving.value = true
  notice.value = ''
  try {
    const payload: SaveProductPayload = {
      name: name.value.trim(),
      description: description.value.trim(),
      price: priceInput.value.trim(),
      categoryId: categoryId.value!,
      coverFileId: coverFileId.value,
    }
    if (isEditing.value) {
      await adminApi.updateProduct(editingId.value!, payload)
      notice.value = '商品已更新'
    } else {
      payload.status = createStatus.value
      await adminApi.createProduct(payload)
      notice.value = '商品已创建'
    }
    resetForm()
    await loadProducts(page.value)
  } catch (err) {
    formError.value = messageOf(err)
  } finally {
    saving.value = false
  }
}

async function onCoverChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  uploadingCover.value = true
  formError.value = ''
  try {
    const uploaded = await adminApi.uploadImage(file)
    coverFileId.value = uploaded.id
    coverUrl.value = uploaded.url
  } catch (err) {
    formError.value = messageOf(err)
  } finally {
    uploadingCover.value = false
    input.value = ''
  }
}

function clearCover(): void {
  coverFileId.value = null
  coverUrl.value = ''
}

async function toggleStatus(product: Product): Promise<void> {
  const next: ProductStatus = product.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE'
  listError.value = ''
  try {
    const updated = await adminApi.changeProductStatus(product.id, next)
    product.status = updated.status
    notice.value = `${updated.name} 已${next === 'ON_SALE' ? '上架' : '下架'}`
  } catch (err) {
    listError.value = messageOf(err)
  }
}

function changeFilter(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  statusFilter.value = value === '' ? null : (value as ProductStatus)
  void loadProducts(1)
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(async () => {
  await loadCategories()
  resetForm()
  await loadProducts(1)
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">商品管理</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin">图片与用户</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>{{ isEditing ? '编辑商品' : '新建商品' }}</h2>
              <p class="panel-note">
                库存和下单后面再做。价格单位元，最多两位小数；封面从本地上传后会存到 MinIO。
              </p>
            </div>
            <button v-if="isEditing" class="secondary-button" type="button" @click="startCreate">
              取消编辑
            </button>
          </div>

          <p v-if="formError" class="form-error">{{ formError }}</p>

          <form class="admin-form" novalidate @submit.prevent="save">
            <div class="admin-form__grid">
              <label class="field">
                <span class="field__label">商品名称</span>
                <input v-model.trim="name" type="text" maxlength="120" placeholder="例如：设计素材包" />
              </label>

              <label class="field">
                <span class="field__label">价格（元）</span>
                <input
                  v-model.trim="priceInput"
                  type="text"
                  inputmode="decimal"
                  placeholder="0.00"
                />
              </label>

              <label class="field">
                <span class="field__label">分类</span>
                <select v-model="categoryId">
                  <option :value="null" disabled>请选择分类</option>
                  <option v-for="category in categories" :key="category.id" :value="category.id">
                    {{ category.name }}
                  </option>
                </select>
              </label>

              <label v-if="!isEditing" class="field">
                <span class="field__label">创建后状态</span>
                <select v-model="createStatus">
                  <option value="DRAFT">草稿（不在商城显示）</option>
                  <option value="ON_SALE">立即上架</option>
                </select>
              </label>
            </div>

            <label class="field">
              <span class="field__label">商品描述</span>
              <textarea
                v-model.trim="description"
                rows="5"
                maxlength="5000"
                placeholder="写清楚商品内容、交付方式等"
              />
            </label>

            <div class="cover-row">
              <div class="cover-preview">
                <img v-if="coverUrl" :src="coverUrl" alt="商品封面" />
                <span v-else class="product-card__placeholder">无封面</span>
              </div>
              <div class="cover-actions">
                <label class="secondary-button upload-label">
                  {{ uploadingCover ? '上传中…' : '上传封面' }}
                  <input
                    type="file"
                    accept="image/jpeg,image/png,image/webp"
                    :disabled="uploadingCover"
                    @change="onCoverChange"
                  />
                </label>
                <button
                  v-if="coverUrl"
                  class="secondary-button"
                  type="button"
                  @click="clearCover"
                >
                  移除封面
                </button>
                <span class="panel-note">JPEG / PNG / WebP，≤5MB</span>
              </div>
            </div>

            <div class="admin-actions">
              <button class="primary-button admin-action" type="submit" :disabled="saving">
                {{ saving ? '保存中…' : isEditing ? '保存修改' : '创建商品' }}
              </button>
            </div>
          </form>
        </section>

        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>商品列表</h2>
              <p class="panel-note">草稿和已下架商品只有管理员能看到。</p>
            </div>
            <select class="filter-select" :value="statusFilter ?? ''" @change="changeFilter">
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="ON_SALE">已上架</option>
              <option value="OFF_SHELF">已下架</option>
            </select>
          </div>

          <p v-if="listError" class="form-error">{{ listError }}</p>
          <p v-if="notice" class="notice">{{ notice }}</p>

          <table class="admin-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>名称</th>
                <th>分类</th>
                <th>价格</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="product in products" :key="product.id">
                <td class="mono">{{ product.id }}</td>
                <td>{{ product.name }}</td>
                <td>{{ product.categoryName }}</td>
                <td class="mono">{{ formatPrice(product.price) }}</td>
                <td>
                  <span class="status-pill" :data-status="product.status">
                    {{ statusLabel(product.status) }}
                  </span>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="secondary-button" type="button" @click="startEdit(product)">
                      编辑
                    </button>
                    <button class="secondary-button" type="button" @click="toggleStatus(product)">
                      {{ product.status === 'ON_SALE' ? '下架' : '上架' }}
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="!listLoading && products.length === 0">
                <td colspan="6" class="panel-note">还没有商品</td>
              </tr>
            </tbody>
          </table>

          <div class="pager">
            <button
              class="secondary-button"
              type="button"
              :disabled="listLoading || page <= 1"
              @click="loadProducts(page - 1)"
            >
              上一页
            </button>
            <span>{{ page }} / {{ totalPages }} · 共 {{ total }} 件</span>
            <button
              class="secondary-button"
              type="button"
              :disabled="listLoading || page >= totalPages"
              @click="loadProducts(page + 1)"
            >
              下一页
            </button>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>
