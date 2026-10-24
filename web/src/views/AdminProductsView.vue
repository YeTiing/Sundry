<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, type SaveProductPayload } from '@/api/admin'
import { catalogApi } from '@/api/catalog'
import type { Category, Product, ProductStatus, ProductType, ReusableImage } from '@/api/types'
import DigitalFileUploader from '@/components/DigitalFileUploader.vue'
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
const deletedView = ref(false)
const listLoading = ref(false)
const listError = ref('')
const notice = ref('')

const editingId = ref<number | null>(null)
const name = ref('')
const description = ref('')
const priceInput = ref('')
const stockInput = ref('')
const categoryId = ref<number | null>(null)
const coverFileId = ref<number | null>(null)
const coverUrl = ref('')
const showCoverReusable = ref(false)
const coverReusableFiles = ref<ReusableImage[]>([])
const loadingCoverReusable = ref(false)
const type = ref<ProductType>('PHYSICAL')
const digitalFileId = ref<number | null>(null)
const originalDigitalFileId = ref<number | null>(null)
const digitalUploading = ref(false)
const createStatus = ref<ProductStatus>('DRAFT')
const uploadingCover = ref(false)
const saving = ref(false)
const createRequestId = ref<string | null>(null)
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

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
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
    const data = deletedView.value
      ? await adminApi.listDeletedProducts(target, PAGE_SIZE)
      : await adminApi.listProducts(statusFilter.value, target, PAGE_SIZE)
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
  stockInput.value = ''
  categoryId.value = categories.value[0]?.id ?? null
  coverFileId.value = null
  coverUrl.value = ''
  showCoverReusable.value = false
  coverReusableFiles.value = []
  type.value = 'PHYSICAL'
  digitalFileId.value = null
  originalDigitalFileId.value = null
  digitalUploading.value = false
  createStatus.value = 'DRAFT'
  createRequestId.value = null
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
  stockInput.value = product.type === 'PHYSICAL' ? String(product.stock) : ''
  categoryId.value = product.categoryId
  coverFileId.value = product.coverFileId
  coverUrl.value = product.coverUrl ?? ''
  type.value = product.type
  digitalFileId.value = product.digitalFileId
  originalDigitalFileId.value = product.digitalFileId
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
  if (type.value === 'PHYSICAL' && !/^\d+$/.test(stockInput.value.trim())) {
    return '实物商品库存必须是非负整数'
  }
  if (digitalUploading.value) return '数字文件还在上传中，请稍候'
  if (type.value === 'DIGITAL' && digitalFileId.value === null) return '请先上传数字文件'
  return ''
}

function onTypeChange(): void {
  if (type.value === 'PHYSICAL') {
    digitalFileId.value = null
  }
}

async function save(): Promise<void> {
  // 双保险：按钮禁用之外，函数本身也拒绝并发进入
  if (saving.value) return

  formError.value = validateForm()
  if (formError.value) return

  saving.value = true
  notice.value = ''
  try {
    const payload: SaveProductPayload = {
      name: name.value.trim(),
      description: description.value.trim(),
      price: priceInput.value.trim(),
      stock: type.value === 'PHYSICAL' ? Number(stockInput.value.trim()) : null,
      categoryId: categoryId.value!,
      coverFileId: coverFileId.value,
      type: type.value,
      digitalFileId: type.value === 'DIGITAL' ? digitalFileId.value : null,
    }
    if (isEditing.value) {
      const previousFileId = originalDigitalFileId.value
      await adminApi.updateProduct(editingId.value!, payload)
      // 换了或移除了数字文件，商品保存后再清理旧文件
      if (previousFileId !== null && previousFileId !== payload.digitalFileId) {
        try {
          await adminApi.deleteFile(previousFileId)
        } catch {
          // 仍被其他商品引用时保留，不影响本次保存
        }
      }
      notice.value = '商品已更新'
    } else {
      // 同一次创建意图复用同一个 key；网络超时后重试不会重复建商品
      const requestId = createRequestId.value ?? newRequestId()
      createRequestId.value = requestId
      payload.status = createStatus.value
      await adminApi.createProduct(payload, requestId)
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

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

async function toggleCoverReusable(): Promise<void> {
  if (showCoverReusable.value) {
    showCoverReusable.value = false
    return
  }
  showCoverReusable.value = true
  loadingCoverReusable.value = true
  formError.value = ''
  try {
    coverReusableFiles.value = await adminApi.listReusableImages()
  } catch (err) {
    formError.value = messageOf(err)
  } finally {
    loadingCoverReusable.value = false
  }
}

async function selectCoverImage(item: ReusableImage): Promise<void> {
  try {
    const reused = await adminApi.reuseImage(item.id)
    coverFileId.value = reused.id
    coverUrl.value = reused.url
    showCoverReusable.value = false
    formError.value = ''
  } catch (err) {
    formError.value = messageOf(err)
  }
}

async function deleteProduct(product: Product): Promise<void> {
  if (!window.confirm(`删除商品「${product.name}」？删除后前后台都不再展示，已绑定的文件会保留。`)) {
    return
  }
  listError.value = ''
  try {
    await adminApi.deleteProduct(product.id)
    notice.value = `${product.name} 已删除`
    if (editingId.value === product.id) {
      resetForm()
    }
    await loadProducts(page.value)
  } catch (err) {
    listError.value = messageOf(err)
  }
}

async function toggleDeletedView(): Promise<void> {
  deletedView.value = !deletedView.value
  notice.value = ''
  listError.value = ''
  if (deletedView.value) {
    resetForm()
  }
  await loadProducts(1)
}

async function restoreProduct(product: Product): Promise<void> {
  listError.value = ''
  try {
    await adminApi.restoreProduct(product.id)
    notice.value = `${product.name} 已恢复（当前为已下架）`
    await loadProducts(page.value)
  } catch (err) {
    listError.value = messageOf(err)
  }
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
        <section v-if="!deletedView" class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>{{ isEditing ? '编辑商品' : '新建商品' }}</h2>
              <p class="panel-note">
                价格单位元，最多两位小数；实物商品需要填写库存，数字商品忽略库存。
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

              <label v-if="type === 'PHYSICAL'" class="field">
                <span class="field__label">库存</span>
                <input
                  v-model.trim="stockInput"
                  type="text"
                  inputmode="numeric"
                  placeholder="例如：100"
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

              <label class="field">
                <span class="field__label">商品类型</span>
                <select v-model="type" @change="onTypeChange">
                  <option value="PHYSICAL">实物商品</option>
                  <option value="DIGITAL">数字商品（付款后下载）</option>
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

            <div v-if="type === 'DIGITAL'" class="field">
              <span class="field__label">数字文件</span>
              <DigitalFileUploader
                v-model="digitalFileId"
                @update:uploading="digitalUploading = $event"
              />
            </div>

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
                <button class="secondary-button" type="button" @click="toggleCoverReusable">
                  {{ showCoverReusable ? '收起已上传' : '选择已上传' }}
                </button>
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

            <div v-if="showCoverReusable" class="cover-reusable">
              <p v-if="loadingCoverReusable" class="panel-note">加载中…</p>
              <p v-else-if="coverReusableFiles.length === 0" class="panel-note">
                最近没有可复用的图片
              </p>
              <button
                v-for="item in coverReusableFiles"
                :key="item.id"
                class="cover-reusable__item"
                type="button"
                :title="`${item.originalName} · ${formatSize(item.sizeBytes)}`"
                @click="selectCoverImage(item)"
              >
                <img :src="item.url" :alt="item.originalName" />
                <span v-if="item.status === 0" class="cover-reusable__tag">已移除</span>
              </button>
            </div>

            <div class="admin-actions">
              <button
                class="primary-button admin-action"
                type="submit"
                :disabled="saving || digitalUploading"
              >
                {{ saving ? '保存中…' : isEditing ? '保存修改' : '创建商品' }}
              </button>
            </div>
          </form>
        </section>

        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>{{ deletedView ? '回收站' : '商品列表' }}</h2>
              <p class="panel-note">
                {{
                  deletedView
                    ? '恢复后为已下架状态，需要重新上架才会在商城展示。'
                    : '草稿和已下架商品只有管理员能看到。'
                }}
              </p>
            </div>
            <div class="panel-head-actions">
              <select
                v-if="!deletedView"
                class="filter-select"
                :value="statusFilter ?? ''"
                @change="changeFilter"
              >
                <option value="">全部状态</option>
                <option value="DRAFT">草稿</option>
                <option value="ON_SALE">已上架</option>
                <option value="OFF_SHELF">已下架</option>
              </select>
              <button class="secondary-button" type="button" @click="toggleDeletedView">
                {{ deletedView ? '返回商品列表' : '回收站' }}
              </button>
            </div>
          </div>

          <p v-if="listError" class="form-error">{{ listError }}</p>
          <p v-if="notice" class="notice">{{ notice }}</p>

          <table class="admin-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>名称</th>
                <th>类型</th>
                <th>分类</th>
                <th>价格</th>
                <th>库存</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="product in products" :key="product.id">
                <td class="mono">{{ product.id }}</td>
                <td>{{ product.name }}</td>
                <td>{{ product.type === 'DIGITAL' ? '数字' : '实物' }}</td>
                <td>{{ product.categoryName }}</td>
                <td class="mono">{{ formatPrice(product.price) }}</td>
                <td class="mono">{{ product.type === 'DIGITAL' ? '—' : product.stock }}</td>
                <td>
                  <span v-if="deletedView" class="status-pill" data-status="DELETED">
                    已删除
                  </span>
                  <span v-else class="status-pill" :data-status="product.status">
                    {{ statusLabel(product.status) }}
                  </span>
                </td>
                <td>
                  <div class="row-actions">
                    <button
                      v-if="deletedView"
                      class="secondary-button"
                      type="button"
                      @click="restoreProduct(product)"
                    >
                      恢复
                    </button>
                    <template v-else>
                      <button class="secondary-button" type="button" @click="startEdit(product)">
                        编辑
                      </button>
                      <button class="secondary-button" type="button" @click="toggleStatus(product)">
                        {{ product.status === 'ON_SALE' ? '下架' : '上架' }}
                      </button>
                      <button
                        class="secondary-button danger-button"
                        type="button"
                        @click="deleteProduct(product)"
                      >
                        删除
                      </button>
                    </template>
                  </div>
                </td>
              </tr>
              <tr v-if="!listLoading && products.length === 0">
                <td colspan="8" class="panel-note">
                  {{ deletedView ? '回收站是空的' : '还没有商品' }}
                </td>
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
