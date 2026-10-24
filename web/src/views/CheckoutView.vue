<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SiteHeader from '@/components/SiteHeader.vue'
import { addressApi } from '@/api/addresses'
import { cartApi } from '@/api/cart'
import { ordersApi } from '@/api/orders'
import type { Address, CartItem } from '@/api/types'

const router = useRouter()

const cartItems = ref<CartItem[]>([])
const addresses = ref<Address[]>([])
const selectedIds = ref<number[]>([])
const addressId = ref<number | null>(null)
const loading = ref(true)
const error = ref('')
const submitting = ref(false)
const createRequestId = ref<string | null>(null)

const availableItems = computed(() => cartItems.value.filter((item) => item.available))
const selectedItems = computed(() =>
  availableItems.value.filter((item) => selectedIds.value.includes(item.id)),
)
const hasPhysical = computed(() =>
  selectedItems.value.some((item) => item.productType === 'PHYSICAL'),
)
const total = computed(() =>
  selectedItems.value.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0),
)
const allSelected = computed(
  () =>
    availableItems.value.length > 0 &&
    selectedItems.value.length === availableItems.value.length,
)

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function toggleItem(id: number): void {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter((entry) => entry !== id)
    : [...selectedIds.value, id]
}

function toggleAll(): void {
  selectedIds.value = allSelected.value ? [] : availableItems.value.map((item) => item.id)
}

function addressText(address: Address): string {
  return `${address.province}${address.city}${address.district}${address.detail}`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [cart, addressList] = await Promise.all([cartApi.list(), addressApi.list()])
    cartItems.value = cart
    addresses.value = addressList
    selectedIds.value = cart.filter((item) => item.available).map((item) => item.id)
    const preferred = addressList.find((address) => address.isDefault) ?? addressList[0]
    addressId.value = preferred?.id ?? null
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = ''
  if (selectedItems.value.length === 0) {
    error.value = '请至少选择一件商品'
    return
  }
  if (hasPhysical.value && addressId.value === null) {
    error.value = '实物商品需要选择收货地址'
    return
  }

  submitting.value = true
  try {
    const requestId = createRequestId.value ?? newRequestId()
    createRequestId.value = requestId
    await ordersApi.create(
      selectedItems.value.map((item) => item.id),
      addressId.value,
      requestId,
    )
    await router.push({ name: 'orders' })
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <RouterLink class="back-link" to="/cart">← 返回购物车</RouterLink>

      <header class="page-head">
        <div>
          <p class="store-kicker">Sundry / 结算</p>
          <h1 class="page-title">确认订单</h1>
        </div>
        <RouterLink class="secondary-button" to="/addresses">管理收货地址</RouterLink>
      </header>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-if="error" class="form-error">{{ error }}</p>

      <template v-if="!loading">
        <section v-if="availableItems.length === 0" class="admin-panel store-empty">
          没有可结算的商品，<RouterLink to="/">去逛逛</RouterLink>。
        </section>

        <template v-else>
          <section class="admin-panel">
            <div class="panel-head">
              <div>
                <h2>商品清单</h2>
                <p class="panel-note">下架或缺货商品不会出现在结算里。</p>
              </div>
              <button class="secondary-button" type="button" @click="toggleAll">
                {{ allSelected ? '取消全选' : '全选' }}
              </button>
            </div>

            <div class="checkout-list">
              <label
                v-for="item in availableItems"
                :key="item.id"
                class="checkout-line"
                :class="{ 'checkout-line--off': !selectedIds.includes(item.id) }"
              >
                <input
                  type="checkbox"
                  :checked="selectedIds.includes(item.id)"
                  @change="toggleItem(item.id)"
                />
                <div class="checkout-line__media">
                  <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.productName" />
                  <span v-else class="product-card__placeholder">
                    {{ item.productName.slice(0, 1) }}
                  </span>
                </div>
                <div class="checkout-line__body">
                  <p class="checkout-line__name">{{ item.productName }}</p>
                  <p class="cart-line__meta">
                    {{ item.productType === 'DIGITAL' ? '数字商品' : `实物 · 库存 ${item.stock}` }}
                  </p>
                </div>
                <p class="mono">{{ formatPrice(item.price) }} × {{ item.quantity }}</p>
                <p class="mono checkout-line__subtotal">
                  {{ formatPrice(Number(item.price) * item.quantity) }}
                </p>
              </label>
            </div>
          </section>

          <section class="admin-panel checkout-section">
            <div class="panel-head">
              <div>
                <h2>收货地址</h2>
                <p class="panel-note">
                  {{
                    hasPhysical
                      ? '含实物商品，必须选择一个收货地址。'
                      : '纯数字订单可以不填地址。'
                  }}
                </p>
              </div>
            </div>

            <p v-if="addresses.length === 0" class="panel-note">
              还没有收货地址，<RouterLink to="/addresses">去添加</RouterLink>。
            </p>

            <div v-else class="address-list">
              <label
                v-for="address in addresses"
                :key="address.id"
                class="address-card address-card--select"
                :class="{ 'is-selected': addressId === address.id }"
              >
                <input
                  v-model="addressId"
                  type="radio"
                  name="address"
                  :value="address.id"
                />
                <div class="address-card__body">
                  <p class="address-card__head">
                    <strong>{{ address.receiverName }}</strong>
                    <span class="mono">{{ address.receiverPhone }}</span>
                    <span v-if="address.isDefault" class="status-pill" data-status="ON_SALE">
                      默认
                    </span>
                  </p>
                  <p class="address-card__text">{{ addressText(address) }}</p>
                </div>
              </label>
            </div>
          </section>

          <footer class="cart-summary admin-panel">
            <div>
              <p class="panel-note">已选 {{ selectedItems.length }} 件商品</p>
              <p class="cart-summary__total mono">应付 {{ formatPrice(total) }}</p>
            </div>
            <button
              class="primary-button cart-summary__action"
              type="button"
              :disabled="submitting"
              @click="submit"
            >
              {{ submitting ? '提交中…' : '提交订单' }}
            </button>
          </footer>
        </template>
      </template>
    </main>
  </div>
</template>
