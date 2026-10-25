<script setup lang="ts">
import type { Product } from '@/api/types'

const emit = defineEmits<{ select: [product: Product] }>()

defineProps<{ products: Product[] }>()

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}
</script>

<template>
  <div class="product-grid">
    <RouterLink
      v-for="product in products"
      :key="product.id"
      class="product-card"
      :to="`/products/${product.id}`"
      @click="emit('select', product)"
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
</template>
