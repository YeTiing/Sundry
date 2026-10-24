<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SiteHeader from '@/components/SiteHeader.vue'
import { cartApi } from '@/api/cart'
import type { CartItem } from '@/api/types'

const router = useRouter()
const items = ref<CartItem[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busyId = ref<number | null>(null)

const total = computed(() =>
  items.value
    .filter((item) => item.available)
    .reduce((sum, item) => sum + Number(item.price) * item.quantity, 0),
)
const availableCount = computed(
  () => items.value.filter((item) => item.available).length,
)

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

function maxQuantity(item: CartItem): number {
  return item.productType === 'PHYSICAL' ? Math.min(item.stock, 999) : 999
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    items.value = await cartApi.list()
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function updateQuantity(item: CartItem, next: number): Promise<void> {
  if (busyId.value !== null) return
  const max = maxQuantity(item)
  if (!Number.isInteger(next) || next < 1 || next > max) {
    error.value = max < 1 ? '该商品暂时缺货' : `数量需在 1 到 ${max} 之间`
    return
  }
  busyId.value = item.id
  error.value = ''
  notice.value = ''
  try {
    const updated = await cartApi.update(item.id, next)
    const index = items.value.findIndex((entry) => entry.id === item.id)
    if (index >= 0) items.value[index] = updated
  } catch (err) {
    error.value = messageOf(err)
    await load()
  } finally {
    busyId.value = null
  }
}

function onQuantityChange(item: CartItem, event: Event): void {
  const input = event.target as HTMLInputElement
  const next = Number(input.value)
  const max = maxQuantity(item)
  const safe = Number.isInteger(next) ? Math.min(Math.max(next, 1), Math.max(max, 1)) : item.quantity
  input.value = String(safe)
  if (safe !== item.quantity) {
    void updateQuantity(item, safe)
  }
}

async function removeItem(item: CartItem): Promise<void> {
  if (!window.confirm(`从购物车移除「${item.productName}」？`)) return
  if (busyId.value !== null) return
  busyId.value = item.id
  error.value = ''
  notice.value = ''
  try {
    await cartApi.remove(item.id)
    items.value = items.value.filter((entry) => entry.id !== item.id)
    notice.value = `已移除「${item.productName}」`
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    busyId.value = null
  }
}

function goCheckout(): void {
  if (availableCount.value === 0) return
  void router.push({ name: 'checkout' })
}

onMounted(load)
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <RouterLink class="back-link" to="/">← 继续购物</RouterLink>

      <header class="page-head">
        <div>
          <p class="store-kicker">Sundry / 购物车</p>
          <h1 class="page-title">购物车</h1>
        </div>
        <RouterLink class="secondary-button" to="/addresses">收货地址</RouterLink>
      </header>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-else-if="error" class="form-error">{{ error }}</p>
      <p v-if="notice" class="notice">{{ notice }}</p>

      <section v-if="!loading && items.length === 0" class="admin-panel store-empty">
        购物车是空的，<RouterLink to="/">去逛逛</RouterLink>。
      </section>

      <template v-else-if="items.length > 0">
        <div class="cart-list">
          <article
            v-for="item in items"
            :key="item.id"
            class="cart-line"
            :class="{ 'cart-line--off': !item.available }"
          >
            <div class="cart-line__media">
              <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.productName" />
              <span v-else class="product-card__placeholder">
                {{ item.productName.slice(0, 1) }}
              </span>
            </div>

            <div class="cart-line__body">
              <RouterLink class="cart-line__name" :to="`/products/${item.productId}`">
                {{ item.productName }}
              </RouterLink>
              <p class="cart-line__meta">
                {{ item.productType === 'DIGITAL' ? '数字商品' : '实物商品' }}
                <span v-if="!item.available" class="status-pill" data-status="OFF_SHELF">
                  已下架
                </span>
              </p>
              <p class="cart-line__price mono">{{ formatPrice(item.price) }}</p>
            </div>

            <div class="cart-line__qty">
              <button
                class="secondary-button qty-button"
                type="button"
                :disabled="!item.available || item.quantity <= 1 || busyId !== null"
                @click="updateQuantity(item, item.quantity - 1)"
              >
                −
              </button>
              <input
                type="number"
                :value="item.quantity"
                :min="1"
                :max="maxQuantity(item)"
                :disabled="!item.available || busyId !== null"
                @change="onQuantityChange(item, $event)"
              />
              <button
                class="secondary-button qty-button"
                type="button"
                :disabled="!item.available || item.quantity >= maxQuantity(item) || busyId !== null"
                @click="updateQuantity(item, item.quantity + 1)"
              >
                +
              </button>
            </div>

            <p class="cart-line__subtotal mono">
              {{ formatPrice(Number(item.price) * item.quantity) }}
            </p>

            <button
              class="secondary-button danger-button"
              type="button"
              :disabled="busyId !== null"
              @click="removeItem(item)"
            >
              移除
            </button>
          </article>
        </div>

        <footer class="cart-summary admin-panel">
          <div>
            <p class="panel-note">
              共 {{ availableCount }} 件可结算商品；下架商品不会计入合计。
            </p>
            <p class="cart-summary__total mono">合计 {{ formatPrice(total) }}</p>
          </div>
          <button
            class="primary-button cart-summary__action"
            type="button"
            :disabled="availableCount === 0"
            @click="goCheckout"
          >
            去结算
          </button>
        </footer>
      </template>
    </main>
  </div>
</template>
