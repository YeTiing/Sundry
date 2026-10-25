<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import SiteHeader from '@/components/SiteHeader.vue'
import ProductGrid from '@/components/ProductGrid.vue'
import { feedApi } from '@/api/feed'
import type {
  AiFeedStatus,
  FeedAlgorithm,
  FeedEventItem,
  FeedPage,
  Product,
} from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useFeedStore } from '@/stores/feed'

const PAGE_SIZE = 12
// LLM 重排实测 4~11 秒，轮询窗口覆盖 15 秒
const POLL_TIMES = 10
const POLL_INTERVAL_MS = 1500

const auth = useAuthStore()
const store = useFeedStore()
const { products, cursor, hasMore } = storeToRefs(store)

const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const aiStatus = ref<AiFeedStatus>('DISABLED')
const aiBuildId = ref<string | null>(null)
const feedBuildId = ref<string | null>(null)
const sentinel = ref<HTMLElement | null>(null)

let observer: IntersectionObserver | null = null
let requestSeq = 0
let pollToken = 0
let scrollTicking = false
let feedTraceId = newTraceId()
let exposed = new Set<number>()

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '推荐加载失败'
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

function newTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `trace-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

function algorithmOf(status: AiFeedStatus): FeedAlgorithm {
  return status === 'READY' ? 'AI' : 'RULE'
}

function startImpression(): void {
  feedTraceId = newTraceId()
  exposed = new Set<number>()
}

/** 曝光按当前列表增量上报；同一 trace 内每个商品只报一次。 */
async function exposeCurrentList(): Promise<void> {
  if (!auth.isAuthenticated) return
  const events: FeedEventItem[] = []
  products.value.forEach((product, index) => {
    if (exposed.has(product.id)) return
    exposed.add(product.id)
    events.push({ type: 'EXPOSURE', productId: product.id, position: index + 1 })
  })
  if (events.length === 0) return

  const payload = {
    traceId: feedTraceId,
    buildId: feedBuildId.value,
    algorithm: algorithmOf(aiStatus.value),
  }
  for (let i = 0; i < events.length; i += 100) {
    void feedApi.recordEvents({ ...payload, events: events.slice(i, i + 100) }, true)
  }
}

function exposeSoon(seq: number): void {
  void nextTick().then(() => {
    if (seq === requestSeq) {
      void exposeCurrentList()
    }
  })
}

function onProductSelect(product: Product): void {
  if (!auth.isAuthenticated) return
  const position = products.value.findIndex((item) => item.id === product.id) + 1
  if (position <= 0) return
  void feedApi.recordEvents({
    traceId: feedTraceId,
    buildId: feedBuildId.value,
    algorithm: algorithmOf(aiStatus.value),
    events: [{ type: 'CLICK', productId: product.id, position }],
  }, true)
}

async function loadFirst(
  refresh: boolean,
  buildId: string | null = null,
): Promise<FeedPage | null> {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await feedApi.getFeed(null, PAGE_SIZE, refresh, buildId)
    if (seq !== requestSeq) return null
    products.value = data.items
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
    aiStatus.value = data.aiStatus
    aiBuildId.value = data.aiBuildId
    feedBuildId.value = data.feedBuildId
    store.loaded = true
    startImpression()
    exposeSoon(seq)
    return data
  } catch (err) {
    if (seq === requestSeq) {
      error.value = messageOf(err)
      hasMore.value = false
    }
    return null
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/**
 * 后台构建期间轮询：只在 PENDING 时继续，READY 立即替换。
 */
async function pollUntilReady(token: number): Promise<void> {
  for (let i = 0; i < POLL_TIMES; i++) {
    await sleep(POLL_INTERVAL_MS)
    if (token !== pollToken) return
    try {
      const page = await feedApi.getFeed(null, PAGE_SIZE, false, aiBuildId.value)
      if (token !== pollToken) return
      if (page.aiStatus === 'READY') {
        products.value = page.items
        cursor.value = page.nextCursor
        hasMore.value = page.hasMore
        aiStatus.value = page.aiStatus
        aiBuildId.value = page.aiBuildId
        feedBuildId.value = page.feedBuildId
        startImpression()
        exposeSoon(requestSeq)
        return
      }
      if (page.aiStatus !== 'PENDING') return
    } catch {
      // 单次轮询失败不打断，继续等下一轮
    }
  }
}

/**
 * 换一批：池子里有变体时毫秒返回；池空才进入 PENDING 轮询。
 */
async function refreshWithAi(): Promise<void> {
  const token = ++pollToken
  const first = await loadFirst(true)
  if (!first || token !== pollToken) return
  if (first.aiStatus !== 'PENDING') return
  await pollUntilReady(token)
}

async function loadMore(): Promise<void> {
  if (loading.value || loadingMore.value || !hasMore.value) return
  const seq = requestSeq
  loadingMore.value = true
  error.value = ''
  try {
    const data = await feedApi.getFeed(cursor.value, PAGE_SIZE, false, aiBuildId.value)
    if (seq !== requestSeq) return
    // 快照切换后游标含义变了，回到第一页重新加载，避免重复或漏项
    if (data.aiBuildId !== aiBuildId.value || data.aiStatus !== aiStatus.value
        || data.feedBuildId !== feedBuildId.value) {
      await loadFirst(false)
      return
    }
    const existing = new Set(products.value.map((product) => product.id))
    products.value = [
      ...products.value,
      ...data.items.filter((product) => !existing.has(product.id)),
    ]
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
    exposeSoon(seq)
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
    const data = await loadFirst(false)
    // 首次访问会先返回规则兜底变体；只在 PENDING 期间轮询等待 AI 升级
    if (data && data.aiStatus === 'PENDING') {
      const token = ++pollToken
      await pollUntilReady(token)
    }
  }
  setupObserver()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  store.scrollY = window.scrollY
  pollToken++
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
        <div class="feed-actions">
          <RouterLink class="secondary-button feed-browse-link" to="/products">
            按分类浏览
          </RouterLink>
          <button class="secondary-button" type="button" :disabled="loading" @click="refreshWithAi">
            换一批
          </button>
        </div>
      </section>

      <p v-if="error" class="form-error">{{ error }}</p>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-else-if="products.length === 0" class="store-empty">
        暂时没有可推荐的商品。
      </p>

      <ProductGrid v-else :products="products" @select="onProductSelect" />

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
