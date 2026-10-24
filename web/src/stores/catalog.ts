import { defineStore } from 'pinia'
import type { Category, Product } from '@/api/types'

/**
 * 商品列表跨路由保存，避免从详情返回时重新加载、丢失滚动位置。
 */
export const useCatalogStore = defineStore('catalog', {
  state: () => ({
    categories: [] as Category[],
    products: [] as Product[],
    activeCategory: null as number | null,
    cursor: null as number | null,
    hasMore: true,
    loaded: false,
    scrollY: 0,
  }),
  actions: {
    reset(category: number | null): void {
      this.activeCategory = category
      this.products = []
      this.cursor = null
      this.hasMore = true
      this.loaded = false
      this.scrollY = 0
    },
  },
})
