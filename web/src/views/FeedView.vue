<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import SiteHeader from '@/components/SiteHeader.vue'
import ProductGrid from '@/components/ProductGrid.vue'
import { feedApi } from '@/api/feed'
import { useFeedStore } from '@/stores/feed'

const PAGE_SIZE = 12

const store = useFeedStore()
const { products, cursor, hasMore } = storeToRefs(store)

const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const sentinel = ref<HTMLElement | null>(null)

let observer: IntersectionObserver | null = null
let requestSeq = 0
let scrollTicking = false

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '推荐加载失败'
}

async function loadFirst(): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  products.value = []
  cursor.value = null
  hasMore.value = true
  try {
    const data = await feedApi.getFeed(null, PAGE_SIZE)
    if (seq !== requestSeq) return
    products.value = data.items
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
    store.loaded = true
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
    const data = await feedApi.getFeed(cursor.value, PAGE_SIZE)
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

function setupObserver(): void {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) {
      void loadMore()
    }
  }, { rootMargin: '400px 0px' })
  if (sentinel.value) observer.observe(sentinel.value)
}

function onScroll(): void {
  if (scrollTicking) return
  scrollTicking = true
  requestAnimationFrame(() => {
    store.scrollY = window.scrollY
    scrollTicking = false
  })
}

function restoreScroll(): void {
  requestAnimationFrame(() => {
    window.scrollTo({ top: store.scrollY })
  })
}

onMounted(async () => {
  if (store.loaded) {
    await nextTick()
    restoreScroll()
  } else {
    await loadFirst()
  }
  setupObserver()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  store.scrollY = window.scrollY
  observer?.disconnect()
  window.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <section class="store-hero">
        <p class="store-kicker">自营 · 单商户</p>
        <h1 class="store-title">为你推荐</h1>
        <p class="store-lede">根据你的浏览和购买偏好，混排热销、新品和探索商品。</p>
        <RouterLink class="secondary-button feed-browse-link" to="/products">
          按分类浏览
        </RouterLink>
      </section>

      <p v-if="error" class="form-error">{{ error }}</p>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-else-if="products.length === 0" class="store-empty">
        暂时没有可推荐的商品。
      </p>

      <ProductGrid v-else :products="products" />

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
