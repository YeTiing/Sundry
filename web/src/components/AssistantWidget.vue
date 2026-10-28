<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { assistantApi } from '@/api/assistant'
import { ApiError } from '@/api/http'
import type { AssistantData } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { getOrCreateSessionId } from '@/utils/session'

interface ChatEntry {
  role: 'user' | 'assistant'
  text: string
  route?: string | null
  routeLabel?: string | null
  query?: Record<string, string>
  data?: AssistantData | null
}

function formatText(text: string): string {
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
  return escaped
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}

const auth = useAuthStore()
const router = useRouter()
const open = ref(false)
const sending = ref(false)
const input = ref('')
const entries = ref<ChatEntry[]>([])
const listRef = ref<HTMLElement | null>(null)
const sessionId = ref(getOrCreateSessionId('assistant'))

// 换账号时清空界面并换会话，避免把上一个账号的订单/分析卡片留给下一个用户
watch(
  () => auth.user?.id,
  (id) => {
    entries.value = []
    open.value = false
    sessionId.value = getOrCreateSessionId(`assistant:${id ?? 'anon'}`)
  },
)

async function scrollToBottom(): Promise<void> {
  await nextTick()
  if (listRef.value) {
    listRef.value.scrollTop = listRef.value.scrollHeight
  }
}

async function send(): Promise<void> {
  const text = input.value.trim()
  if (!text || sending.value) return
  entries.value.push({ role: 'user', text })
  input.value = ''
  sending.value = true
  await scrollToBottom()
  try {
    const reply = await assistantApi.chat(text, sessionId.value)
    entries.value.push({
      role: 'assistant',
      text: reply.reply,
      route: reply.route,
      routeLabel: reply.routeLabel,
      query: reply.query,
      data: reply.data,
    })
  } catch (err) {
    const status = err instanceof ApiError ? err.status : 0
    entries.value.push({
      role: 'assistant',
      text: status === 429 ? '问得太快啦，歇一会儿再试。' : '助手暂时不可用，请稍后再试。',
    })
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function go(entry: ChatEntry): void {
  if (!entry.route) return
  void router.push({ name: entry.route, query: entry.query ?? {} })
  open.value = false
}

function goProduct(id: number): void {
  void router.push({ name: 'product', params: { id } })
  open.value = false
}
</script>

<template>
  <div v-if="auth.isAuthenticated" class="assistant">
    <section v-if="open" class="assistant-panel" aria-label="站内助手">
      <header class="assistant-head">
        <div>
          <p class="assistant-kicker">Sundry Assistant</p>
          <h2 class="assistant-title">站内助手</h2>
        </div>
        <button class="assistant-close" type="button" aria-label="关闭助手" @click="open = false">
          ×
        </button>
      </header>

      <div ref="listRef" class="assistant-list">
        <p v-if="entries.length === 0" class="assistant-hint">
          试试问我「我的订单在哪」「打开购物车」。
        </p>
        <div
          v-for="(entry, index) in entries"
          :key="index"
          class="assistant-entry"
          :data-role="entry.role"
        >
          <p v-if="entry.role === 'user'" class="assistant-text">{{ entry.text }}</p>
          <template v-else-if="entry.data?.type === 'orders'">
            <p class="assistant-sub">{{ entry.data?.criteria }} · 共 {{ entry.data?.total }} 单</p>
            <ul v-if="entry.data?.orders?.length" class="order-cards">
              <li v-for="(order, orderIndex) in entry.data?.orders ?? []" :key="orderIndex" class="order-card">
                <p class="order-products">{{ order.products }}</p>
                <div class="order-meta">
                  <span class="order-status" :data-status="order.statusCode">{{ order.status }}</span>
                  <span class="order-amount">¥{{ order.amount }}</span>
                  <span class="order-date">{{ order.date }}</span>
                </div>
              </li>
            </ul>
            <p v-else class="assistant-text">没有符合条件的订单。</p>
          </template>
          <div v-else-if="entry.data?.type === 'products'" class="assistant-products">
            <p class="assistant-sub">{{ entry.data?.criteria }}</p>
            <ul v-if="entry.data?.products?.length" class="product-cards">
              <li v-for="product in entry.data?.products ?? []" :key="product.id">
                <button type="button" class="product-card" @click="goProduct(product.id)">
                  <img
                    v-if="product.coverUrl"
                    :src="product.coverUrl"
                    alt=""
                    class="product-cover"
                  />
                  <span v-else class="product-cover product-cover-empty">S</span>
                  <span class="product-info">
                    <span class="product-name">{{ product.name }}</span>
                    <span class="product-meta">
                      <span>¥{{ product.price }}</span>
                      <span>{{ product.category }}</span>
                    </span>
                  </span>
                </button>
              </li>
            </ul>
            <p v-else class="assistant-text">没有找到符合条件的在售商品。</p>
          </div>
          <div v-else-if="entry.data?.type === 'purchaseSummary'" class="assistant-summary">
            <p class="assistant-sub">{{ entry.data?.criteria }}</p>
            <div class="summary-grid">
              <div><strong>{{ entry.data?.orderCount }}</strong><span>单</span></div>
              <div><strong>{{ entry.data?.itemCount }}</strong><span>件</span></div>
              <div><strong>¥{{ entry.data?.totalAmount }}</strong><span>累计支付</span></div>
            </div>
          </div>
          <p v-else class="assistant-text" v-html="formatText(entry.text)"></p>
          <button v-if="entry.route" class="assistant-link" type="button" @click="go(entry)">
            打开{{ entry.routeLabel ?? '页面' }}
          </button>
        </div>
      </div>

      <form class="assistant-form" @submit.prevent="send">
        <input
          v-model="input"
          class="assistant-input"
          maxlength="500"
          placeholder="问我点什么…"
          aria-label="输入消息"
        />
        <button class="assistant-send" type="submit" :disabled="sending || !input.trim()">
          {{ sending ? '…' : '发送' }}
        </button>
      </form>
    </section>

    <button v-if="!open" class="assistant-toggle" type="button" @click="open = true">
      助手
    </button>
  </div>
</template>

<style scoped>
.assistant {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 60;
}

.assistant-toggle {
  width: 64px;
  height: 64px;
  border: 1px solid var(--ink);
  border-radius: 50%;
  background: var(--stamp);
  color: var(--sheet);
  font-family: var(--font-sans);
  font-size: 15px;
  letter-spacing: 0.08em;
  cursor: pointer;
  box-shadow: 0 12px 30px rgba(23, 23, 18, 0.24);
  transition: transform 160ms ease, box-shadow 160ms ease;
}

.assistant-toggle:hover {
  transform: translateY(-2px);
  box-shadow: 0 16px 34px rgba(23, 23, 18, 0.3);
}

.assistant-toggle:focus-visible,
.assistant-close:focus-visible,
.assistant-send:focus-visible,
.assistant-input:focus-visible,
.assistant-link:focus-visible {
  outline: 2px solid var(--stamp);
  outline-offset: 2px;
}

.assistant-panel {
  display: flex;
  width: 360px;
  max-height: min(560px, 72vh);
  flex-direction: column;
  border: 1px solid var(--rule-strong);
  background: var(--sheet);
  box-shadow: 0 24px 60px rgba(23, 23, 18, 0.22);
}

.assistant-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 18px 12px;
  border-bottom: 1px solid var(--rule);
}

.assistant-kicker {
  margin: 0 0 4px;
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.assistant-title {
  margin: 0;
  font-size: 18px;
  letter-spacing: -0.02em;
}

.assistant-close {
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.assistant-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px;
}

.assistant-hint {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.assistant-entry {
  margin-bottom: 12px;
}

.assistant-entry[data-role='user'] {
  text-align: right;
}

.assistant-text {
  display: inline-block;
  margin: 0;
  padding: 9px 12px;
  border: 1px solid var(--rule);
  background: var(--paper);
  font-size: 13px;
  line-height: 1.6;
  text-align: left;
  white-space: pre-wrap;
}

.assistant-entry[data-role='user'] .assistant-text {
  border-color: var(--ink-soft);
  background: var(--ink);
  color: var(--sheet);
}

.assistant-sub {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12px;
}

.order-cards {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.order-card {
  padding: 9px 11px;
  border: 1px solid var(--rule);
  background: var(--paper);
}

.order-products {
  margin: 0 0 5px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.45;
}

.order-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  color: var(--muted);
  font-size: 11px;
}

.order-amount {
  color: var(--ink);
  font-weight: 600;
}

.order-status {
  padding: 1px 6px;
  border: 1px solid var(--rule-strong);
  color: var(--ink);
}

.order-status[data-status='CANCELLED'],
.order-status[data-status='REFUNDED'] {
  color: var(--muted);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.summary-grid > div {
  padding: 10px 8px;
  border: 1px solid var(--rule);
  background: var(--paper);
  text-align: center;
}

.summary-grid strong {
  display: block;
  font-size: 16px;
}

.summary-grid span {
  color: var(--muted);
  font-size: 11px;
}

.product-cards {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.product-card {
  display: flex;
  width: 100%;
  gap: 10px;
  padding: 8px;
  border: 1px solid var(--rule);
  background: var(--paper);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.product-card:hover,
.product-card:focus-visible {
  border-color: var(--stamp);
  outline: none;
}

.product-cover {
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  border: 1px solid var(--rule);
  object-fit: cover;
}

.product-cover-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 12px;
}

.product-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
}

.product-name {
  display: -webkit-box;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.product-meta {
  display: flex;
  gap: 8px;
  color: var(--muted);
  font-size: 11px;
}

.product-meta > span:first-child {
  color: var(--stamp);
  font-weight: 600;
}

.assistant-link {
  display: block;
  margin-top: 8px;
  padding: 7px 12px;
  border: 1px solid var(--stamp);
  background: transparent;
  color: var(--stamp);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.04em;
  cursor: pointer;
}

.assistant-link:hover {
  background: var(--stamp);
  color: var(--sheet);
}

.assistant-form {
  display: flex;
  gap: 8px;
  padding: 12px 14px 14px;
  border-top: 1px solid var(--rule);
}

.assistant-input {
  min-width: 0;
  flex: 1;
  padding: 9px 10px;
  border: 1px solid var(--rule-strong);
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-sans);
  font-size: 13px;
}

.assistant-send {
  padding: 9px 14px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--sheet);
  font-size: 13px;
  cursor: pointer;
}

.assistant-send:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

@media (max-width: 520px) {
  .assistant {
    right: 16px;
    bottom: 16px;
  }

  .assistant-panel {
    width: calc(100vw - 32px);
  }
}
</style>
