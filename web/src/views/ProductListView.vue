<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SiteHeader from '@/components/SiteHeader.vue'
import { catalogApi } from '@/api/catalog'
import type { Category, Product } from '@/api/types'

const PAGE_SIZE = 12

const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const activeCategory = ref<number | null>(null)
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await catalogApi.categories()
  } catch {
    // 分类加载失败不影响商品列表
  }
}

async function loadProducts(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await catalogApi.products(activeCategory.value, target, PAGE_SIZE)
    products.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (err) {
    error.value = err instanceof Error ? err.message : '商品加载失败'
  } finally {
    loading.value = false
  }
}

function selectCategory(id: number | null): void {
  if (activeCategory.value === id) return
  activeCategory.value = id
  void loadProducts(1)
}

onMounted(() => {
  void loadCategories()
  void loadProducts(1)
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

      <div v-if="totalPages > 1" class="pager">
        <button
          class="secondary-button"
          type="button"
          :disabled="loading || page <= 1"
          @click="loadProducts(page - 1)"
        >
          上一页
        </button>
        <span>{{ page }} / {{ totalPages }} · 共 {{ total }} 件</span>
        <button
          class="secondary-button"
          type="button"
          :disabled="loading || page >= totalPages"
          @click="loadProducts(page + 1)"
        >
          下一页
        </button>
      </div>
    </main>
  </div>
</template>
