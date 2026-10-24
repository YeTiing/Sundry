<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import SiteHeader from '@/components/SiteHeader.vue'
import { catalogApi } from '@/api/catalog'
import type { Category, Product } from '@/api/types'

const PAGE_SIZE = 12

const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const activeCategory = ref<number | null>(null)
const cursor = ref<number | null>(null)
const hasMore = ref(true)
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const sentinel = ref<HTMLElement | null>(null)

let observer: IntersectionObserver | null = null
let requestSeq = 0

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '商品加载失败'
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await catalogApi.categories()
  } catch {
    // 分类加载失败不影响商品列表
  }
}

async function loadFirst(): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  products.value = []
  cursor.value = null
  hasMore.value = true
  try {
    const data = await catalogApi.scrollProducts(activeCategory.value, null, PAGE_SIZE)
    if (seq !== requestSeq) return
    products.value = data.items
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
  } catch (err) {
    if (seq === requestSeq) {
      error.value = messageOf(err)
      hasMore.value = false
    }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (loading.value || loadingMore.value || !hasMore.value) return
  const seq = requestSeq
  loadingMore.value = true
  error.value = ''
  try {
    const data = await catalogApi.scrollProducts(activeCategory.value, cursor.value, PAGE_SIZE)
    if (seq !== requestSeq) return
    const existing = new Set(products.value.map((product) => product.id))
    products.value = [
      ...products.value,
      ...data.items.filter((product) => !existing.has(product.id)),
    ]
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
  } catch (err) {
    if (seq === requestSeq) error.value = messageOf(err)
  } finally {
    if (seq === requestSeq) loadingMore.value = false
  }
}

function selectCategory(id: number | null): void {
  if (activeCategory.value === id) return
  activeCategory.value = id
  void loadFirst()
}

function setupObserver(): void {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) {
      void loadMore()
    }
  }, { rootMargin: '400px 0px' })
  if (sentinel.value) observer.observe(sentinel.value)
}

onMounted(() => {
  void loadCategories()
  void loadFirst()
  setupObserver()
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <section class="store-hero">
        <p class="store-kicker">自营 · 单商户</p>
        <h1 class="store-title">Sundry</h1>
        <p class="store-lede">自己上架，自己发货。看到的都是已上架商品。</p>
      </section>

      <nav class="category-bar" aria-label="商品分类">
        <button
          type="button"
          class="category-chip"
          :class="{ 'is-active': activeCategory === null }"
          @click="selectCategory(null)"
        >
          全部
        </button>
        <button
          v-for="category in categories"
          :key="category.id"
          type="button"
          class="category-chip"
          :class="{ 'is-active': activeCategory === category.id }"
          @click="selectCategory(category.id)"
        >
          {{ category.name }}
        </button>
      </nav>

      <p v-if="error" class="form-error">{{ error }}</p>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-else-if="products.length === 0" class="store-empty">
        这个分类下还没有上架商品。
      </p>

      <div v-else class="product-grid">
        <RouterLink
          v-for="product in products"
          :key="product.id"
          class="product-card"
          :to="`/products/${product.id}`"
        >
          <div class="product-card__media">
            <img v-if="product.coverUrl" :src="product.coverUrl" :alt="product.name" loading="lazy" />
            <span v-else class="product-card__placeholder">{{ product.name.slice(0, 1) }}</span>
          </div>
          <div class="product-card__body">
            <span class="product-card__category">{{ product.categoryName }}</span>
            <h2 class="product-card__name">{{ product.name }}</h2>
            <p class="product-card__price">{{ formatPrice(product.price) }}</p>
          </div>
        </RouterLink>
      </div>

      <div ref="sentinel" class="scroll-sentinel" aria-hidden="true"></div>

      <div v-if="products.length > 0" class="load-more-bar">
        <span v-if="loadingMore" class="panel-note">加载中…</span>
        <span v-else-if="!hasMore" class="panel-note">没有更多了</span>
        <button
          v-else
          class="secondary-button load-more-button"
          type="button"
          @click="loadMore"
        >
          加载更多
        </button>
      </div>
    </main>
  </div>
</template>
