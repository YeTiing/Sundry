<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SiteHeader from '@/components/SiteHeader.vue'
import { ordersApi } from '@/api/orders'
import type { Order, OrderItem, OrderStatus, RefundStatus } from '@/api/types'

const PAGE_SIZE = 10

const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待支付',
  PAID: '待发货',
  PACKING: '打包中',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUND_REQUESTED: '退款审核中',
  RETURN_REQUESTED: '退货审核中',
  RETURNING: '待寄回',
  RETURN_SHIPPED: '退货已寄回',
  REFUNDED: '已退款',
}

const REFUND_LABELS: Record<RefundStatus, string> = {
  PENDING: '待商家审核',
  APPROVED: '商家已通过',
  RETURNING: '商家已同意，请寄回',
  RETURN_SHIPPED: '退货已寄回，等待商家收货',
  COMPLETED: '退货完成',
  REJECTED: '商家已拒绝',
  WITHDRAWN: '已撤销',
}

const orders = ref<Order[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busyOrderNo = ref<string | null>(null)
const refundTarget = ref<string | null>(null)
const refundReason = ref('')
const returnTarget = ref<string | null>(null)
const returnReason = ref('')
const returnShipTarget = ref<string | null>(null)
const returnTrackingNo = ref('')

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

function isPaidLike(status: OrderStatus): boolean {
  return status === 'PAID' || status === 'PACKING' || status === 'SHIPPED'
    || status === 'COMPLETED' || status === 'REFUND_REQUESTED'
    || status === 'RETURN_REQUESTED' || status === 'RETURNING'
    || status === 'RETURN_SHIPPED'
}

function withinReturnWindow(order: Order): boolean {
  if (order.status !== 'COMPLETED' || !order.completedAt) return true
  const completed = new Date(order.completedAt).getTime()
  if (Number.isNaN(completed)) return true
  return Date.now() - completed <= 7 * 24 * 60 * 60 * 1000
}

function canReturn(order: Order): boolean {
  if (!order.hasPhysical) return false
  if (order.status === 'SHIPPED') return true
  return order.status === 'COMPLETED' && withinReturnWindow(order)
}

function canDownload(order: Order): boolean {
  return isPaidLike(order.status) && order.status !== 'REFUNDED'
}

function canRefund(order: Order): boolean {
  return order.hasPhysical && (order.status === 'PAID' || order.status === 'PACKING')
}

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await ordersApi.list(target, PAGE_SIZE)
    orders.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
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

function pay(order: Order): void {
  void act(order, '支付成功（模拟支付）', () => ordersApi.pay(order.orderNo))
}

function cancel(order: Order): void {
  if (!window.confirm(`取消订单 ${order.orderNo}？取消后库存会回补。`)) return
  void act(order, '订单已取消', () => ordersApi.cancel(order.orderNo))
}

function confirmReceipt(order: Order): void {
  if (!window.confirm('确认已收到商品？确认后订单完成。')) return
  void act(order, '已确认收货', () => ordersApi.confirm(order.orderNo))
}

function openRefund(order: Order): void {
  refundTarget.value = order.orderNo
  refundReason.value = ''
  error.value = ''
  notice.value = ''
}

function closeRefund(): void {
  refundTarget.value = null
  refundReason.value = ''
}

async function submitRefund(order: Order): Promise<void> {
  const reason = refundReason.value.trim()
  if (!reason) {
    error.value = '请填写退款原因'
    return
  }
  if (reason.length > 200) {
    error.value = '退款原因最多 200 个字符'
    return
  }
  const ok = await act(order, '退款申请已提交，等待商家审核',
    () => ordersApi.refund(order.orderNo, reason))
  if (ok) closeRefund()
}

function withdrawRefund(order: Order): void {
  if (!window.confirm('撤销退款申请？订单会回到申请前的状态。')) return
  void act(order, '已撤销退款申请', () => ordersApi.withdrawRefund(order.orderNo))
}

function openReturn(order: Order): void {
  returnTarget.value = order.orderNo
  returnReason.value = ''
  refundTarget.value = null
  returnShipTarget.value = null
  error.value = ''
  notice.value = ''
}

function closeReturn(): void {
  returnTarget.value = null
  returnReason.value = ''
}

async function submitReturn(order: Order): Promise<void> {
  const reason = returnReason.value.trim()
  if (!reason) {
    error.value = '请填写退货原因'
    return
  }
  if (reason.length > 200) {
    error.value = '退货原因最多 200 个字符'
    return
  }
  const ok = await act(order, '退货申请已提交，等待商家审核',
    () => ordersApi.applyReturn(order.orderNo, reason))
  if (ok) closeReturn()
}

function withdrawReturn(order: Order): void {
  if (!window.confirm('撤销退货申请？订单会回到申请前的状态。')) return
  void act(order, '已撤销退货申请', () => ordersApi.withdrawReturn(order.orderNo))
}

function openReturnShip(order: Order): void {
  returnShipTarget.value = order.orderNo
  returnTrackingNo.value = ''
  returnTarget.value = null
  refundTarget.value = null
  error.value = ''
  notice.value = ''
}

function closeReturnShip(): void {
  returnShipTarget.value = null
  returnTrackingNo.value = ''
}

async function submitReturnShip(order: Order): Promise<void> {
  const tracking = returnTrackingNo.value.trim()
  if (!tracking) {
    error.value = '请填写退货快递单号'
    return
  }
  if (tracking.length > 64) {
    error.value = '快递单号最多 64 个字符'
    return
  }
  const ok = await act(order, '退货单号已提交，等待商家收货',
    () => ordersApi.shipReturn(order.orderNo, tracking))
  if (ok) closeReturnShip()
}

async function download(order: Order, item: OrderItem): Promise<void> {
  const win = window.open('', '_blank')
  if (win) win.opener = null
  try {
    const info = await ordersApi.download(order.orderNo, item.id)
    if (win) {
      win.location.href = info.url
    } else {
      window.location.href = info.url
    }
  } catch (err) {
    win?.close()
    error.value = messageOf(err)
  }
}

onMounted(() => {
  void load(1)
})
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <RouterLink class="back-link" to="/">← 继续购物</RouterLink>

      <header class="page-head">
        <div>
          <p class="store-kicker">Sundry / 订单</p>
          <h1 class="page-title">我的订单</h1>
        </div>
        <RouterLink class="secondary-button" to="/cart">购物车</RouterLink>
      </header>

      <p v-if="error" class="form-error">{{ error }}</p>
      <p v-if="notice" class="notice">{{ notice }}</p>
      <p v-if="loading" class="store-empty">加载中…</p>

      <section v-else-if="orders.length === 0" class="admin-panel store-empty">
        还没有订单，<RouterLink to="/">去逛逛</RouterLink>。
      </section>

      <div v-else class="order-list">
        <article v-for="order in orders" :key="order.orderNo" class="order-card admin-panel">
          <header class="order-card__head">
            <div>
              <p class="order-card__no mono">
                {{ order.orderNo }}
                <span v-if="order.orderSource === 'SECKILL'" class="seckill-badge">秒杀</span>
              </p>
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
                <RouterLink class="order-item__name" :to="`/products/${item.productId}`">
                  {{ item.productName }}
                </RouterLink>
                <p class="cart-line__meta">
                  {{ item.productType === 'DIGITAL' ? '数字商品' : '实物商品' }}
                </p>
              </div>
              <p class="mono">{{ formatPrice(item.price) }} × {{ item.quantity }}</p>
              <button
                v-if="item.productType === 'DIGITAL' && canDownload(order)"
                class="secondary-button"
                type="button"
                @click="download(order, item)"
              >
                下载
              </button>
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
              {{ order.refundKind === 'RETURN' ? '退货申请' : '退款申请' }}
              <span v-if="order.refundStatus" class="refund-tag" :data-status="order.refundStatus">
                {{ REFUND_LABELS[order.refundStatus] }}
              </span>
            </p>
            <p>原因：{{ order.refundReason }}</p>
            <p v-if="order.returnTrackingNo" class="mono">
              退货单号：{{ order.returnTrackingNo }}
            </p>
            <p v-if="order.refundAmount" class="mono">
              金额：{{ formatPrice(order.refundAmount) }}
            </p>
            <p v-if="order.refundReviewRemark">商家回复：{{ order.refundReviewRemark }}</p>
            <p v-if="order.refundedAt" class="panel-note">
              退款时间 {{ formatDate(order.refundedAt) }}
            </p>
          </section>

          <footer class="row-actions order-actions">
            <button
              v-if="order.status === 'PENDING_PAYMENT'"
              class="primary-button order-action"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="pay(order)"
            >
              去支付
            </button>
            <button
              v-if="order.status === 'PENDING_PAYMENT'"
              class="secondary-button"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="cancel(order)"
            >
              取消订单
            </button>
            <button
              v-if="order.status === 'SHIPPED' || (order.status === 'PAID' && !order.hasPhysical)"
              class="primary-button order-action"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="confirmReceipt(order)"
            >
              确认收货
            </button>
            <button
              v-if="canRefund(order)"
              class="secondary-button"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="openRefund(order)"
            >
              申请退款
            </button>
            <button
              v-if="order.status === 'REFUND_REQUESTED'"
              class="secondary-button"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="withdrawRefund(order)"
            >
              撤销退款申请
            </button>
            <button
              v-if="canReturn(order)"
              class="secondary-button"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="openReturn(order)"
            >
              申请退货
            </button>
            <button
              v-if="order.status === 'RETURN_REQUESTED'"
              class="secondary-button"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="withdrawReturn(order)"
            >
              撤销退货申请
            </button>
            <button
              v-if="order.status === 'RETURNING'"
              class="primary-button order-action"
              type="button"
              :disabled="busyOrderNo !== null"
              @click="openReturnShip(order)"
            >
              填写退货单号
            </button>
            <span class="order-total mono">合计 {{ formatPrice(order.totalAmount) }}</span>
          </footer>

          <form
            v-if="returnTarget === order.orderNo"
            class="refund-form"
            novalidate
            @submit.prevent="submitReturn(order)"
          >
            <label class="field">
              <span class="field__label">退货原因</span>
              <textarea
                v-model.trim="returnReason"
                rows="3"
                maxlength="200"
                placeholder="说明退货原因，商家会看到"
              />
            </label>
            <div class="row-actions">
              <button
                class="primary-button order-action"
                type="submit"
                :disabled="busyOrderNo !== null"
              >
                提交退货申请
              </button>
              <button class="secondary-button" type="button" @click="closeReturn">取消</button>
            </div>
          </form>

          <form
            v-if="returnShipTarget === order.orderNo"
            class="refund-form"
            novalidate
            @submit.prevent="submitReturnShip(order)"
          >
            <label class="field">
              <span class="field__label">退货快递单号</span>
              <input
                v-model.trim="returnTrackingNo"
                type="text"
                maxlength="64"
                placeholder="寄回后填写，例如：YT123456"
              />
            </label>
            <div class="row-actions">
              <button
                class="primary-button order-action"
                type="submit"
                :disabled="busyOrderNo !== null"
              >
                提交退货单号
              </button>
              <button class="secondary-button" type="button" @click="closeReturnShip">取消</button>
            </div>
          </form>

          <form
            v-if="refundTarget === order.orderNo"
            class="refund-form"
            novalidate
            @submit.prevent="submitRefund(order)"
          >
            <label class="field">
              <span class="field__label">退款原因</span>
              <textarea
                v-model.trim="refundReason"
                rows="3"
                maxlength="200"
                placeholder="写清楚退款原因，商家会看到"
              />
            </label>
            <div class="row-actions">
              <button
                class="primary-button order-action"
                type="submit"
                :disabled="busyOrderNo !== null"
              >
                提交申请
              </button>
              <button class="secondary-button" type="button" @click="closeRefund">取消</button>
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
    </main>
  </div>
</template>
