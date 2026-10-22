<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import SiteHeader from '@/components/SiteHeader.vue'
import { catalogApi } from '@/api/catalog'
import type { Product } from '@/api/types'

const route = useRoute()
const product = ref<Product | null>(null)
const loading = ref(true)
const error = ref('')

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

async function load(): Promise<void> {
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
  } catch (err) {
    product.value = null
    error.value = err instanceof Error ? err.message : '商品加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => route.params.id, load, { immediate: true })
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
          </span>
          <h1 class="product-detail__name">{{ product.name }}</h1>
          <p class="product-detail__price">{{ formatPrice(product.price) }}</p>
          <p class="product-detail__desc">{{ product.description }}</p>
        </div>
      </article>
    </main>
  </div>
</template>
