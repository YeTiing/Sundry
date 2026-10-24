import { defineStore } from 'pinia'
import type { Product } from '@/api/types'

/**
 * 首页 Feed 跨路由保存，返回时保留列表和滚动位置。
 */
export const useFeedStore = defineStore('feed', {
  state: () => ({
    products: [] as Product[],
    cursor: null as number | null,
    hasMore: true,
    loaded: false,
    scrollY: 0,
  }),
  actions: {
    reset(): void {
      this.products = []
      this.cursor = null
      this.hasMore = true
      this.loaded = false
      this.scrollY = 0
    },
  },
})
