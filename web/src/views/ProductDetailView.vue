<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SiteHeader from '@/components/SiteHeader.vue'
import { cartApi } from '@/api/cart'
import { catalogApi } from '@/api/catalog'
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

          <div class="buy-row">
            <template v-if="seckill">
              <button class="primary-button buy-row__action" type="button" disabled>
                秒杀进行中 · 抢购通道准备中
              </button>
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
          <p v-if="cartError" class="form-error">{{ cartError }}</p>
          <p v-if="cartNotice" class="notice">{{ cartNotice }}</p>
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
