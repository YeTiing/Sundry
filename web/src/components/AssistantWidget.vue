<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { assistantApi } from '@/api/assistant'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface ChatEntry {
  role: 'user' | 'assistant'
  text: string
  route?: string | null
  routeLabel?: string | null
  query?: Record<string, string>
}

const auth = useAuthStore()
const router = useRouter()
const open = ref(false)
const sending = ref(false)
const input = ref('')
const entries = ref<ChatEntry[]>([])
const listRef = ref<HTMLElement | null>(null)
const sessionId = ref(newSessionId())

function newSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

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
          <p class="assistant-text">{{ entry.text }}</p>
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
}

.assistant-entry[data-role='user'] .assistant-text {
  border-color: var(--ink-soft);
  background: var(--ink);
  color: var(--sheet);
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
