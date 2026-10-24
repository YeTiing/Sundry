<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminOrdersApi } from '@/api/adminOrders'
import type { Order, OrderStatus, RefundStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const PAGE_SIZE = 20

const STATUS_OPTIONS: { value: OrderStatus | ''; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PENDING_PAYMENT', label: '待支付' },
  { value: 'PAID', label: '待发货' },
  { value: 'PACKING', label: '打包中' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUND_REQUESTED', label: '退款审核中' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CANCELLED', label: '已取消' },
]

const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待支付',
  PAID: '待发货',
  PACKING: '打包中',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUND_REQUESTED: '退款审核中',
  REFUNDED: '已退款',
}

const REFUND_LABELS: Record<RefundStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  WITHDRAWN: '用户已撤销',
}

const auth = useAuthStore()
const router = useRouter()

const orders = ref<Order[]>([])
const page = ref(1)
const total = ref(0)
const statusFilter = ref<OrderStatus | ''>('')
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busyOrderNo = ref<string | null>(null)

const shipTarget = ref<string | null>(null)
const trackingNo = ref('')
const reviewTarget = ref<string | null>(null)
const reviewAction = ref<'approve' | 'reject'>('approve')
const reviewRemark = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

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

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await adminOrdersApi.list(
      statusFilter.value === '' ? null : statusFilter.value,
      target,
      PAGE_SIZE,
    )
    orders.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function changeFilter(event: Event): void {
  statusFilter.value = (event.target as HTMLSelectElement).value as OrderStatus | ''
  void load(1)
}

async function act(order: Order, successMessage: string, action: () => Promise<unknown>): Promise<boolean> {
  if (busyOrderNo.value !== null) return false
  busyOrderNo.value = order.orderNo
  error.value = ''
  notice.value = ''
  try {
    await action()
    await load(page.value)
    notice.value = successMessage
    return true
  } catch (err) {
    error.value = messageOf(err)
    return false
  } finally {
    busyOrderNo.value = null
  }
}

function pack(order: Order): void {
  void act(order, `订单 ${order.orderNo} 已打包`, () => adminOrdersApi.pack(order.orderNo))
}

function openShip(order: Order): void {
  shipTarget.value = order.orderNo
  trackingNo.value = ''
  reviewTarget.value = null
  error.value = ''
  notice.value = ''
}

function closeShip(): void {
  shipTarget.value = null
  trackingNo.value = ''
}

async function submitShip(order: Order): Promise<void> {
  const value = trackingNo.value.trim()
  if (!value) {
    error.value = '发货必须填写快递单号'
    return
  }
  if (value.length > 64) {
    error.value = '快递单号最多 64 个字符'
    return
  }
  const ok = await act(order, `订单 ${order.orderNo} 已发货`,
    () => adminOrdersApi.ship(order.orderNo, value))
  if (ok) closeShip()
}

function openReview(order: Order, action: 'approve' | 'reject'): void {
  reviewTarget.value = order.orderNo
  reviewAction.value = action
  reviewRemark.value = ''
  shipTarget.value = null
  error.value = ''
  notice.value = ''
}

function closeReview(): void {
  reviewTarget.value = null
  reviewRemark.value = ''
}

async function submitReview(order: Order): Promise<void> {
  const remark = reviewRemark.value.trim()
  if (reviewAction.value === 'reject' && !remark) {
    error.value = '拒绝退款必须填写理由'
    return
  }
  if (remark.length > 200) {
    error.value = '审核意见最多 200 个字符'
    return
  }

  const approving = reviewAction.value === 'approve'
  const ok = await act(
    order,
    approving ? '已通过退款，库存已回补' : '已拒绝退款，订单回到原状态',
    () => approving
      ? adminOrdersApi.approveRefund(order.orderNo, remark || null)
      : adminOrdersApi.rejectRefund(order.orderNo, remark),
  )
  if (ok) closeReview()
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(() => {
  void load(1)
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">订单管理</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin/products">商品管理</RouterLink>
          <RouterLink to="/admin">图片与用户</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>订单列表</h2>
              <p class="panel-note">
                打包 → 发货；退款申请需要在这里通过或拒绝。
              </p>
            </div>
            <div class="panel-head-actions">
              <select class="filter-select" :value="statusFilter" @change="changeFilter">
                <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
              <button class="secondary-button" type="button" :disabled="loading" @click="load(page)">
                刷新
              </button>
            </div>
          </div>

          <p v-if="error" class="form-error">{{ error }}</p>
          <p v-if="notice" class="notice">{{ notice }}</p>
          <p v-if="loading" class="panel-note">加载中…</p>
          <p v-else-if="orders.length === 0" class="panel-note">没有符合条件的订单。</p>

          <div v-else class="order-list">
            <article v-for="order in orders" :key="order.orderNo" class="order-card">
              <header class="order-card__head">
                <div>
                  <p class="order-card__no mono">{{ order.orderNo }}</p>
                  <p class="panel-note">下单时间 {{ formatDate(order.createdAt) }}</p>
                </div>
                <span class="order-status" :data-status="order.status">
                  {{ STATUS_LABELS[order.status] }}
                </span>
              </header>

              <div class="order-items">
                <div v-for="item in order.items" :key="item.id" class="order-item">
                  <div class="order-item__media">
                    <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.productName" />
                    <span v-else class="product-card__placeholder">
                      {{ item.productName.slice(0, 1) }}
                    </span>
                  </div>
                  <div class="order-item__body">
                    <p class="order-item__name">{{ item.productName }}</p>
                    <p class="cart-line__meta">
                      {{ item.productType === 'DIGITAL' ? '数字商品' : '实物商品' }}
                    </p>
                  </div>
                  <p class="mono">{{ formatPrice(item.price) }} × {{ item.quantity }}</p>
                </div>
              </div>

              <dl v-if="order.receiverAddress" class="order-meta">
                <div>
                  <dt>收货人</dt>
                  <dd>{{ order.receiverName }} · {{ order.receiverPhone }}</dd>
                </div>
                <div>
                  <dt>地址</dt>
                  <dd>{{ order.receiverAddress }}</dd>
                </div>
                <div v-if="order.trackingNo">
                  <dt>快递单号</dt>
                  <dd class="mono">{{ order.trackingNo }}</dd>
                </div>
              </dl>

              <section v-if="order.refundReason" class="refund-box">
                <p class="refund-box__head">
                  退款申请
                  <span
                    v-if="order.refundStatus"
                    class="refund-tag"
                    :data-status="order.refundStatus"
                  >
                    {{ REFUND_LABELS[order.refundStatus] }}
                  </span>
                </p>
                <p>原因：{{ order.refundReason }}</p>
                <p v-if="order.refundAmount" class="mono">
                  金额：{{ formatPrice(order.refundAmount) }}
                </p>
                <p v-if="order.refundReviewRemark">审核意见：{{ order.refundReviewRemark }}</p>
                <p class="panel-note">申请时间 {{ formatDate(order.refundRequestedAt) }}</p>
              </section>

              <footer class="row-actions order-actions">
                <button
                  v-if="order.status === 'PAID' && order.hasPhysical"
                  class="primary-button order-action"
                  type="button"
                  :disabled="busyOrderNo !== null"
                  @click="pack(order)"
                >
                  打包
                </button>
                <button
                  v-if="order.status === 'PACKING'"
                  class="primary-button order-action"
                  type="button"
                  :disabled="busyOrderNo !== null"
                  @click="openShip(order)"
                >
                  发货
                </button>
                <button
                  v-if="order.status === 'REFUND_REQUESTED'"
                  class="primary-button order-action"
                  type="button"
                  :disabled="busyOrderNo !== null"
                  @click="openReview(order, 'approve')"
                >
                  通过退款
                </button>
                <button
                  v-if="order.status === 'REFUND_REQUESTED'"
                  class="secondary-button danger-button"
                  type="button"
                  :disabled="busyOrderNo !== null"
                  @click="openReview(order, 'reject')"
                >
                  拒绝退款
                </button>
                <span class="order-total mono">合计 {{ formatPrice(order.totalAmount) }}</span>
              </footer>

              <form
                v-if="shipTarget === order.orderNo"
                class="refund-form"
                novalidate
                @submit.prevent="submitShip(order)"
              >
                <label class="field">
                  <span class="field__label">快递单号</span>
                  <input v-model.trim="trackingNo" type="text" maxlength="64" placeholder="例如：SF1234567890" />
                </label>
                <div class="row-actions">
                  <button class="primary-button order-action" type="submit" :disabled="busyOrderNo !== null">
                    确认发货
                  </button>
                  <button class="secondary-button" type="button" @click="closeShip">取消</button>
                </div>
              </form>

              <form
                v-if="reviewTarget === order.orderNo"
                class="refund-form"
                novalidate
                @submit.prevent="submitReview(order)"
              >
                <label class="field">
                  <span class="field__label">
                    {{ reviewAction === 'approve' ? '审核备注（选填）' : '拒绝理由（必填）' }}
                  </span>
                  <textarea
                    v-model.trim="reviewRemark"
                    rows="3"
                    maxlength="200"
                    :placeholder="reviewAction === 'approve' ? '可以补充说明' : '告诉用户拒绝原因'"
                  />
                </label>
                <div class="row-actions">
                  <button
                    class="primary-button order-action"
                    type="submit"
                    :disabled="busyOrderNo !== null"
                  >
                    {{ reviewAction === 'approve' ? '确认通过' : '确认拒绝' }}
                  </button>
                  <button class="secondary-button" type="button" @click="closeReview">取消</button>
                </div>
              </form>
            </article>
          </div>

          <div v-if="totalPages > 1" class="pager">
            <button
              class="secondary-button"
              type="button"
              :disabled="loading || page <= 1"
              @click="load(page - 1)"
            >
              上一页
            </button>
            <span>{{ page }} / {{ totalPages }} · 共 {{ total }} 单</span>
            <button
              class="secondary-button"
              type="button"
              :disabled="loading || page >= totalPages"
              @click="load(page + 1)"
            >
              下一页
            </button>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>
