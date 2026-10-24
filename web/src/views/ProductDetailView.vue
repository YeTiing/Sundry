<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SiteHeader from '@/components/SiteHeader.vue'
import { adminApi } from '@/api/admin'
import { cartApi } from '@/api/cart'
import { catalogApi } from '@/api/catalog'
import { behaviorApi } from '@/api/feed'
import { seckillApi } from '@/api/seckill'
import { shopApi } from '@/api/shop'
import { useAuthStore } from '@/stores/auth'
import type { Product, SeckillActivity, ShopView } from '@/api/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const product = ref<Product | null>(null)
const seckill = ref<SeckillActivity | null>(null)
const shop = ref<ShopView | null>(null)
const loading = ref(true)
const error = ref('')
const quantity = ref(1)
const adding = ref(false)
const cartNotice = ref('')
const cartError = ref('')
let reportedViewId: number | null = null

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

async function addToCart(): Promise<void> {
  if (!product.value || adding.value) return
  if (!auth.isAuthenticated) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const count = Number(quantity.value)
  if (!Number.isInteger(count) || count < 1) {
    cartError.value = '数量必须是正整数'
    return
  }
  if (product.value.type === 'PHYSICAL' && count > product.value.stock) {
    cartError.value = `库存只剩 ${product.value.stock} 件`
    return
  }
  adding.value = true
  cartError.value = ''
  cartNotice.value = ''
  try {
    await cartApi.add(product.value.id, count)
    cartNotice.value = '已加入购物车'
  } catch (err) {
    cartError.value = messageOf(err)
  } finally {
    adding.value = false
  }
}

const isAdmin = computed(() => auth.isAdmin)
const adminBusy = ref(false)
const adminError = ref('')
const adminNotice = ref('')

const statusLabel = computed(() => {
  if (!product.value) return ''
  return product.value.status === 'ON_SALE' ? '已上架'
    : product.value.status === 'OFF_SHELF' ? '已下架' : '草稿'
})

function editProduct(): void {
  if (!product.value) return
  void router.push({ name: 'admin-products', query: { edit: String(product.value.id) } })
}

async function downloadProduct(): Promise<void> {
  if (!product.value) return
  const win = window.open('', '_blank')
  if (win) win.opener = null
  adminError.value = ''
  adminNotice.value = ''
  try {
    const info = await adminApi.downloadProduct(product.value.id)
    if (win) {
      win.location.href = info.url
    } else {
      window.location.href = info.url
    }
  } catch (err) {
    win?.close()
    adminError.value = messageOf(err)
  }
}

async function toggleStatus(): Promise<void> {
  if (!product.value || adminBusy.value) return
  adminBusy.value = true
  adminError.value = ''
  adminNotice.value = ''
  const next = product.value.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE'
  try {
    product.value = await adminApi.changeProductStatus(product.value.id, next)
    adminNotice.value = next === 'ON_SALE' ? '商品已上架' : '商品已下架'
  } catch (err) {
    adminError.value = messageOf(err)
  } finally {
    adminBusy.value = false
  }
}

async function deleteProduct(): Promise<void> {
  if (!product.value || adminBusy.value) return
  if (!window.confirm(`删除商品「${product.value.name}」？删除后会进入回收站。`)) return
  adminBusy.value = true
  adminError.value = ''
  try {
    await adminApi.deleteProduct(product.value.id)
    await router.push({ name: 'admin-products' })
  } catch (err) {
    adminError.value = messageOf(err)
  } finally {
    adminBusy.value = false
  }
}

async function load(): Promise<void> {
  quantity.value = 1
  cartNotice.value = ''
  cartError.value = ''
  const id = Number(route.params.id)
  if (!Number.isInteger(id) || id <= 0) {
    product.value = null
    loading.value = false
    error.value = '商品不存在或已下架'
    return
  }
  loading.value = true
  error.value = ''
  try {
    product.value = await catalogApi.product(id)
    try {
      seckill.value = await seckillApi.productActivity(id)
    } catch {
      // 没有进行中的秒杀活动时后端返回 404，按普通商品处理
      seckill.value = null
    }
    if (auth.isAuthenticated && !auth.isAdmin && reportedViewId !== id) {
      reportedViewId = id
      void behaviorApi.recordView(id).catch(() => {
        // 行为上报失败不影响商品详情
      })
    }
  } catch (err) {
    product.value = null
    seckill.value = null
    error.value = err instanceof Error ? err.message : '商品加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => route.params.id, load, { immediate: true })

onMounted(async () => {
  try {
    shop.value = await shopApi.get()
  } catch {
    // 店铺名片加载失败不影响商品详情
  }
})
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <RouterLink class="back-link" to="/">← 返回商品列表</RouterLink>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-else-if="error" class="form-error">{{ error }}</p>

      <article v-else-if="product" class="product-detail">
        <div class="product-detail__media">
          <img v-if="product.coverUrl" :src="product.coverUrl" :alt="product.name" />
          <span v-else class="product-card__placeholder">{{ product.name.slice(0, 1) }}</span>
        </div>

        <div class="product-detail__body">
          <span class="product-card__category">
            {{ product.categoryName }} · {{ product.type === 'DIGITAL' ? '数字商品' : '实物商品' }}
            <span v-if="seckill" class="seckill-badge">秒杀</span>
          </span>
          <span v-if="isAdmin" class="status-pill" :data-status="product.status">
            {{ statusLabel }}
          </span>
          <h1 class="product-detail__name">{{ product.name }}</h1>
          <p v-if="seckill" class="product-detail__price seckill-price">
            {{ formatPrice(seckill.seckillPrice) }}
            <span class="price-original">{{ formatPrice(product.price) }}</span>
          </p>
          <p v-else class="product-detail__price">{{ formatPrice(product.price) }}</p>
          <p v-if="seckill" class="seckill-note">
            秒杀进行中 · 每人限购 {{ seckill.perUserLimit }} 件 · 剩余 {{ seckill.remaining }} 件
            · 活动至 {{ new Date(seckill.endAt).toLocaleString('zh-CN') }}
          </p>
          <p v-if="product.type === 'PHYSICAL'" class="panel-note">
            {{ product.stock > 0 ? `库存 ${product.stock} 件` : '暂时缺货' }}
          </p>
          <p v-else class="panel-note">数字商品，付款后可下载</p>
          <p class="product-detail__desc">{{ product.description }}</p>

          <div v-if="isAdmin" class="buy-row admin-product-actions">
            <button
              class="primary-button buy-row__action"
              type="button"
              @click="editProduct"
            >
              编辑商品
            </button>
            <button
              v-if="product.type === 'DIGITAL' && product.digitalFileId"
              class="secondary-button"
              type="button"
              @click="downloadProduct"
            >
              下载数字文件
            </button>
            <button
              class="secondary-button"
              type="button"
              :disabled="adminBusy"
              @click="toggleStatus"
            >
              {{ product.status === 'ON_SALE' ? '下架' : '上架' }}
            </button>
            <button
              class="secondary-button danger-button"
              type="button"
              :disabled="adminBusy"
              @click="deleteProduct"
            >
              删除商品
            </button>
          </div>

          <div v-else class="buy-row">
            <template v-if="seckill">
              <RouterLink
                class="primary-button buy-row__action"
                :to="`/seckill/${seckill.id}`"
              >
                立即秒杀
              </RouterLink>
            </template>
            <template v-else>
              <label class="field buy-row__qty">
                <span class="field__label">数量</span>
                <input
                  v-model.number="quantity"
                  type="number"
                  min="1"
                  :max="product.type === 'PHYSICAL' ? product.stock : undefined"
                />
              </label>
              <button
                class="primary-button buy-row__action"
                type="button"
                :disabled="adding || (product.type === 'PHYSICAL' && product.stock <= 0)"
                @click="addToCart"
              >
                {{ adding ? '加入中…' : '加入购物车' }}
              </button>
            </template>
            <RouterLink class="secondary-button" to="/cart">去购物车</RouterLink>
          </div>
          <p v-if="isAdmin && adminError" class="form-error">{{ adminError }}</p>
          <p v-if="isAdmin && adminNotice" class="notice">{{ adminNotice }}</p>
          <p v-if="!isAdmin && cartError" class="form-error">{{ cartError }}</p>
          <p v-if="!isAdmin && cartNotice" class="notice">{{ cartNotice }}</p>
        </div>
      </article>

      <section v-if="shop" class="shop-card">
        <img v-if="shop.logoUrl" class="shop-card__logo" :src="shop.logoUrl" alt="店铺 Logo" />
        <span v-else class="shop-card__logo shop-card__logo--text">
          {{ shop.shopName.slice(0, 1) }}
        </span>
        <div class="shop-card__body">
          <h2>{{ shop.shopName }}</h2>
          <p v-if="shop.description">{{ shop.description }}</p>
          <p v-if="shop.contact" class="shop-card__contact">{{ shop.contact }}</p>
        </div>
      </section>
    </main>
  </div>
</template>
