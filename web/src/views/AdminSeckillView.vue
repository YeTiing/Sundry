<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { adminSeckillApi, type SaveSeckillPayload } from '@/api/seckill'
import type { Product, SeckillActivity, SeckillStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const PAGE_SIZE = 20
const PRICE_PATTERN = /^\d{1,8}(\.\d{1,2})?$/

const STATUS_OPTIONS: { value: SeckillStatus | ''; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '已启用' },
  { value: 'ENDED', label: '已结束' },
  { value: 'CANCELLED', label: '已取消' },
]

const STATUS_LABELS: Record<SeckillStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '已启用',
  ENDED: '已结束',
  CANCELLED: '已取消',
}

const auth = useAuthStore()
const router = useRouter()

const products = ref<Product[]>([])
const activities = ref<SeckillActivity[]>([])
const page = ref(1)
const total = ref(0)
const statusFilter = ref<SeckillStatus | ''>('')
const loading = ref(true)
const error = ref('')
const notice = ref('')
const saving = ref(false)
const busyId = ref<number | null>(null)

const editingId = ref<number | null>(null)
const productId = ref<number | null>(null)
const priceInput = ref('')
const stockInput = ref('')
const limitInput = ref('1')
const startInput = ref('')
const endInput = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const isEditing = computed(() => editingId.value !== null)

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function toInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toApiTime(value: string): string {
  return value.length === 16 ? `${value}:00` : value
}

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

async function loadProducts(): Promise<void> {
  try {
    const data = await adminApi.listProducts('ON_SALE', 1, 100)
    products.value = data.items
  } catch (err) {
    error.value = messageOf(err)
  }
}

async function loadActivities(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await adminSeckillApi.list(
      statusFilter.value === '' ? null : statusFilter.value,
      target,
      PAGE_SIZE,
    )
    activities.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  editingId.value = null
  productId.value = products.value[0]?.id ?? null
  priceInput.value = ''
  stockInput.value = ''
  limitInput.value = '1'
  startInput.value = toInput(new Date(Date.now() + 60 * 60 * 1000))
  endInput.value = toInput(new Date(Date.now() + 2 * 60 * 60 * 1000))
  error.value = ''
}

function startEdit(activity: SeckillActivity): void {
  editingId.value = activity.id
  productId.value = activity.productId
  priceInput.value = Number(activity.seckillPrice).toFixed(2)
  stockInput.value = String(activity.stock)
  limitInput.value = String(activity.perUserLimit)
  startInput.value = activity.startAt.slice(0, 16)
  endInput.value = activity.endAt.slice(0, 16)
  error.value = ''
  notice.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function validate(): string {
  if (productId.value === null) return '请选择商品'
  if (!PRICE_PATTERN.test(priceInput.value.trim())) return '秒杀价格式不正确'
  if (!/^\d+$/.test(stockInput.value.trim()) || Number(stockInput.value) < 1) {
    return '活动库存必须是正整数'
  }
  if (!/^\d+$/.test(limitInput.value.trim()) || Number(limitInput.value) < 1) {
    return '每人限购必须是正整数'
  }
  if (!startInput.value || !endInput.value) return '请选择活动时间'
  if (new Date(startInput.value).getTime() >= new Date(endInput.value).getTime()) {
    return '结束时间必须晚于开始时间'
  }
  return ''
}

async function save(): Promise<void> {
  if (saving.value) return
  error.value = validate()
  if (error.value) return

  const payload: SaveSeckillPayload = {
    productId: productId.value!,
    seckillPrice: priceInput.value.trim(),
    stock: Number(stockInput.value),
    perUserLimit: Number(limitInput.value),
    startAt: toApiTime(startInput.value),
    endAt: toApiTime(endInput.value),
  }

  saving.value = true
  notice.value = ''
  try {
    if (isEditing.value) {
      await adminSeckillApi.update(editingId.value!, payload)
      notice.value = '活动已更新'
    } else {
      await adminSeckillApi.create(payload)
      notice.value = '活动已创建（草稿）'
    }
    resetForm()
    await loadActivities(page.value)
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    saving.value = false
  }
}

async function act(activity: SeckillActivity, message: string,
                   action: () => Promise<unknown>): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = activity.id
  error.value = ''
  notice.value = ''
  try {
    await action()
    await loadActivities(page.value)
    notice.value = message
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    busyId.value = null
  }
}

function activate(activity: SeckillActivity): void {
  if (!window.confirm(`启用「${activity.productName}」的秒杀活动？`)) return
  void act(activity, '活动已启用',
    () => adminSeckillApi.activate(activity.id))
}

function cancel(activity: SeckillActivity): void {
  if (!window.confirm(`取消「${activity.productName}」的秒杀活动？取消后无法恢复。`)) return
  void act(activity, '活动已取消',
    () => adminSeckillApi.cancel(activity.id))
}

function end(activity: SeckillActivity): void {
  if (!window.confirm(`提前结束「${activity.productName}」的秒杀活动？`)) return
  void act(activity, '活动已结束',
    () => adminSeckillApi.end(activity.id))
}

function changeFilter(event: Event): void {
  statusFilter.value = (event.target as HTMLSelectElement).value as SeckillStatus | ''
  void loadActivities(1)
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(async () => {
  await loadProducts()
  resetForm()
  await loadActivities(1)
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">秒杀活动</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin/products">商品管理</RouterLink>
          <RouterLink to="/admin/categories">分类管理</RouterLink>
          <RouterLink to="/admin/orders">订单管理</RouterLink>
          <RouterLink to="/admin">图片与用户</RouterLink>
          <RouterLink to="/admin/nl2sql">经营分析</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>{{ isEditing ? '编辑活动' : '新建活动' }}</h2>
              <p class="panel-note">
                草稿可以编辑；启用后按开始/结束时间生效。同一商品不能有重叠的进行中活动。
              </p>
            </div>
            <button v-if="isEditing" class="secondary-button" type="button" @click="resetForm">
              取消编辑
            </button>
          </div>

          <p v-if="error" class="form-error">{{ error }}</p>
          <p v-if="notice" class="notice">{{ notice }}</p>

          <form class="admin-form" novalidate @submit.prevent="save">
            <div class="admin-form__grid">
              <label class="field">
                <span class="field__label">商品（仅已上架）</span>
                <select v-model="productId">
                  <option :value="null" disabled>请选择商品</option>
                  <option v-for="product in products" :key="product.id" :value="product.id">
                    {{ product.name }} · {{ formatPrice(product.price) }} · 库存 {{ product.stock }}
                  </option>
                </select>
              </label>

              <label class="field">
                <span class="field__label">秒杀价（元）</span>
                <input v-model.trim="priceInput" type="text" inputmode="decimal" placeholder="0.00" />
              </label>

              <label class="field">
                <span class="field__label">活动库存</span>
                <input v-model.trim="stockInput" type="text" inputmode="numeric" placeholder="例如：100" />
              </label>

              <label class="field">
                <span class="field__label">每人限购</span>
                <input v-model.trim="limitInput" type="text" disabled />
                <span class="field__hint">当前版本固定为 1 件</span>
              </label>

              <label class="field">
                <span class="field__label">开始时间</span>
                <input v-model="startInput" type="datetime-local" />
              </label>

              <label class="field">
                <span class="field__label">结束时间</span>
                <input v-model="endInput" type="datetime-local" />
              </label>
            </div>

            <div class="admin-actions">
              <button class="primary-button admin-action" type="submit" :disabled="saving">
                {{ saving ? '保存中…' : isEditing ? '保存修改' : '创建活动' }}
              </button>
            </div>
          </form>
        </section>

        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>活动列表</h2>
              <p class="panel-note">活动库存是销售配额；实物下单时还会校验商品库存。</p>
            </div>
            <div class="panel-head-actions">
              <select class="filter-select" :value="statusFilter" @change="changeFilter">
                <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
              <button class="secondary-button" type="button" :disabled="loading" @click="loadActivities(page)">
                刷新
              </button>
            </div>
          </div>

          <p v-if="loading" class="panel-note">加载中…</p>
          <p v-else-if="activities.length === 0" class="panel-note">还没有秒杀活动。</p>

          <table v-else class="admin-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>商品</th>
                <th>秒杀价 / 原价</th>
                <th>库存（余 / 总）</th>
                <th>限购</th>
                <th>活动时间</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="activity in activities" :key="activity.id">
                <td class="mono">{{ activity.id }}</td>
                <td>
                  {{ activity.productName }}
                  <span class="panel-note">{{ activity.productType === 'DIGITAL' ? '数字' : '实物' }}</span>
                </td>
                <td class="mono">
                  {{ formatPrice(activity.seckillPrice) }} / {{ formatPrice(activity.productPrice) }}
                </td>
                <td class="mono">{{ activity.remaining }} / {{ activity.stock }}</td>
                <td class="mono">{{ activity.perUserLimit }}</td>
                <td>
                  {{ formatDate(activity.startAt) }}
                  <br />
                  至 {{ formatDate(activity.endAt) }}
                </td>
                <td>
                  <span class="seckill-status" :data-status="activity.status">
                    {{ STATUS_LABELS[activity.status] }}
                  </span>
                  <span v-if="activity.activeNow" class="seckill-status" data-status="LIVE">
                    进行中
                  </span>
                </td>
                <td>
                  <div class="row-actions">
                    <button
                      v-if="activity.status === 'DRAFT'"
                      class="secondary-button"
                      type="button"
                      @click="startEdit(activity)"
                    >
                      编辑
                    </button>
                    <button
                      v-if="activity.status === 'DRAFT'"
                      class="secondary-button"
                      type="button"
                      :disabled="busyId !== null"
                      @click="activate(activity)"
                    >
                      启用
                    </button>
                    <button
                      v-if="activity.status === 'ACTIVE'"
                      class="secondary-button"
                      type="button"
                      :disabled="busyId !== null"
                      @click="end(activity)"
                    >
                      结束
                    </button>
                    <button
                      v-if="activity.status === 'DRAFT' || activity.status === 'ACTIVE'"
                      class="secondary-button danger-button"
                      type="button"
                      :disabled="busyId !== null"
                      @click="cancel(activity)"
                    >
                      取消
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>

          <div v-if="totalPages > 1" class="pager">
            <button
              class="secondary-button"
              type="button"
              :disabled="loading || page <= 1"
              @click="loadActivities(page - 1)"
            >
              上一页
            </button>
            <span>{{ page }} / {{ totalPages }} · 共 {{ total }} 个活动</span>
            <button
              class="secondary-button"
              type="button"
              :disabled="loading || page >= totalPages"
              @click="loadActivities(page + 1)"
            >
              下一页
            </button>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>
