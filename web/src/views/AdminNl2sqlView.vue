<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminNl2sqlApi } from '@/api/adminNl2sql'
import type { Nl2sqlLogView, Nl2sqlQueryResult, Nl2sqlVocabulary } from '@/api/adminNl2sql'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const STATUS_OPTIONS = ['', 'SUCCESS', 'REJECTED', 'UNSUPPORTED', 'TIMEOUT', 'DB_ERROR', 'AI_UNAVAILABLE']
const STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  REJECTED: '计划拒绝',
  UNSUPPORTED: '不支持',
  TIMEOUT: '超时',
  DB_ERROR: '数据库错误',
  AI_UNAVAILABLE: 'AI 不可用',
}
const PRESET_LABELS: Record<string, string> = {
  TODAY: '今天',
  YESTERDAY: '昨天',
  LAST_7D: '近 7 天',
  LAST_30D: '近 30 天',
  THIS_MONTH: '本月',
  LAST_MONTH: '上月',
  LAST_QUARTER: '上季度',
}

const auth = useAuthStore()
const router = useRouter()

const vocabulary = ref<Nl2sqlVocabulary | null>(null)
const question = ref('')
const sessionId = ref(newSessionId())
const loading = ref(false)
const result = ref<Nl2sqlQueryResult | null>(null)
const error = ref('')
const errorCode = ref('')

const logs = ref<Nl2sqlLogView[]>([])
const logStatus = ref('')
const logsLoading = ref(false)

function newSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function errorMessage(err: unknown): { code: string; message: string } {
  if (!(err instanceof ApiError)) {
    return { code: 'UNKNOWN', message: '请求失败，请稍后再试' }
  }
  switch (err.code) {
    case 'NL2SQL_DISABLED':
      return { code: err.code, message: '经营分析暂未开放：启动后端时设置 NL2SQL_ENABLED=true' }
    case 'NL2SQL_UNSUPPORTED':
      return { code: err.code, message: `问题超出词汇表：${err.message}` }
    case 'NL2SQL_PLAN_REJECTED':
      return { code: err.code, message: `计划被拒绝：${err.message}` }
    case 'NL2SQL_UNAVAILABLE':
      return { code: err.code, message: '规划服务暂不可用，请稍后再试' }
    case 'NL2SQL_QUERY_FAILED':
      return { code: err.code, message: '查询执行失败，请换个问法或稍后再试' }
    case 'RATE_LIMITED':
      return { code: err.code, message: '问得太快，休息一下再试' }
    default:
      return { code: err.code, message: err.message }
  }
}

async function loadVocabulary(): Promise<void> {
  try {
    vocabulary.value = await adminNl2sqlApi.vocabulary()
  } catch {
    vocabulary.value = null
  }
}

async function loadLogs(): Promise<void> {
  logsLoading.value = true
  try {
    const page = await adminNl2sqlApi.logs(1, 10, logStatus.value || null)
    logs.value = page.items
  } catch {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

async function ask(): Promise<void> {
  const text = question.value.trim()
  if (!text || loading.value) return
  loading.value = true
  error.value = ''
  errorCode.value = ''
  result.value = null
  try {
    result.value = await adminNl2sqlApi.query(text, sessionId.value)
  } catch (err) {
    const info = errorMessage(err)
    error.value = info.message
    errorCode.value = info.code
  } finally {
    loading.value = false
    await loadLogs()
  }
}

function metricLabel(key: string): string {
  return vocabulary.value?.metrics.find((item) => item.key === key)?.label ?? key
}

function dimensionLabel(key: string): string {
  return vocabulary.value?.dimensions.find((item) => item.key === key)?.label ?? key
}

function filterLabel(key: string): string {
  return vocabulary.value?.filters.find((item) => item.key === key)?.label ?? key
}

function presetLabel(preset: string | null): string {
  if (!preset) return '默认（近 30 天）'
  return PRESET_LABELS[preset] ?? preset
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

function display(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'number') {
    return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
  }
  return String(value)
}

function dateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(() => {
  void loadVocabulary()
  void loadLogs()
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">经营分析</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin/products">商品管理</RouterLink>
          <RouterLink to="/admin/categories">分类管理</RouterLink>
          <RouterLink to="/admin/orders">订单管理</RouterLink>
          <RouterLink to="/admin/seckill">秒杀活动</RouterLink>
          <RouterLink to="/admin/feed">推荐指标</RouterLink>
          <RouterLink to="/admin/nl2sql">经营分析</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <div class="nl2sql-layout">
          <section class="admin-panel">
            <div class="panel-head">
              <h2>问一句经营数据</h2>
              <span class="panel-note">会话 {{ sessionId.slice(0, 8) }}</span>
            </div>
            <p class="panel-note">
              只支持词汇表里的指标和维度。模型只生成查询计划，SQL 由服务端按白名单拼装并参数化执行。
            </p>

            <div v-if="vocabulary" class="nl2sql-vocab">
              <div class="nl2sql-vocab-group">
                <span class="nl2sql-vocab-label">指标</span>
                <span v-for="metric in vocabulary.metrics" :key="metric.key" class="nl2sql-chip">
                  {{ metric.label }}<small v-if="metric.unit">/{{ metric.unit }}</small>
                </span>
              </div>
              <div class="nl2sql-vocab-group">
                <span class="nl2sql-vocab-label">维度</span>
                <span v-for="dimension in vocabulary.dimensions" :key="dimension.key" class="nl2sql-chip">
                  {{ dimension.label }}
                </span>
              </div>
              <div class="nl2sql-vocab-group">
                <span class="nl2sql-vocab-label">时间</span>
                <span v-for="preset in vocabulary.timePresets" :key="preset" class="nl2sql-chip">
                  {{ presetLabel(preset) }}
                </span>
              </div>
              <div class="nl2sql-vocab-group">
                <span class="nl2sql-vocab-label">筛选</span>
                <span v-for="filter in vocabulary.filters" :key="filter.key" class="nl2sql-chip">
                  {{ filter.label }}
                </span>
              </div>
            </div>
            <p v-else class="panel-note">词汇表加载失败，但仍可以尝试提问。</p>

            <form class="nl2sql-form" @submit.prevent="ask">
              <textarea
                v-model="question"
                class="nl2sql-input"
                maxlength="500"
                rows="2"
                placeholder="例如：最近 30 天各分类的销售额"
              ></textarea>
              <div class="nl2sql-form-actions">
                <span class="panel-note">{{ question.length }}/500 · 每次查询 10 次/分钟</span>
                <button class="primary-button" type="submit" :disabled="loading || !question.trim()">
                  {{ loading ? '分析中…' : '开始分析' }}
                </button>
              </div>
            </form>

            <div v-if="error" class="nl2sql-error">
              <span class="nl2sql-code">{{ errorCode }}</span>
              <span>{{ error }}</span>
            </div>

            <template v-if="result">
              <p class="nl2sql-answer">{{ result.answer }}</p>
              <div class="nl2sql-table-wrap">
                <table class="nl2sql-table">
                  <thead>
                    <tr>
                      <th v-for="column in result.columns" :key="column.key">
                        {{ column.label }}<small v-if="column.unit">/{{ column.unit }}</small>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, index) in result.rows" :key="index">
                      <td v-for="column in result.columns" :key="column.key">
                        {{ display(row[column.key]) }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-if="result.rows.length === 0" class="panel-note">没有符合条件的数据。</p>

              <div class="nl2sql-plan">
                <div><span>指标</span>{{ result.plan.metrics.map(metricLabel).join('、') || '—' }}</div>
                <div><span>维度</span>{{ result.plan.dimensions.map(dimensionLabel).join('、') || '不分组' }}</div>
                <div>
                  <span>筛选</span>
                  {{ result.plan.filters.length
                    ? result.plan.filters.map((filter) => `${filterLabel(filter.key)}=${display(filter.value)}`).join('、')
                    : '无' }}
                </div>
                <div>
                  <span>时间</span>
                  {{ presetLabel(result.plan.time.preset) }}
                  （{{ result.plan.time.start.slice(0, 10) }} ~ {{ result.plan.time.end.slice(0, 10) }}）
                </div>
                <div><span>粒度</span>{{ result.plan.granularity }} · 最多 {{ result.plan.limit }} 行</div>
              </div>
              <p class="panel-note">
                行数 {{ result.rowCount }} · 耗时 {{ result.latencyMs }}ms · trace {{ result.traceId.slice(0, 8) }}
              </p>
            </template>
          </section>

          <aside class="admin-panel nl2sql-audit">
            <div class="panel-head">
              <h2>最近审计</h2>
              <div class="panel-head-actions">
                <select v-model="logStatus" class="nl2sql-select" @change="loadLogs">
                  <option v-for="status in STATUS_OPTIONS" :key="status" :value="status">
                    {{ status ? statusLabel(status) : '全部状态' }}
                  </option>
                </select>
                <button class="secondary-button" type="button" :disabled="logsLoading" @click="loadLogs">
                  刷新
                </button>
              </div>
            </div>
            <p class="panel-note">全量记录在 nl2sql_log，这里显示最近 10 条。</p>
            <p v-if="logsLoading" class="panel-note">加载中…</p>
            <p v-else-if="logs.length === 0" class="panel-note">还没有查询记录。</p>
            <div v-else class="nl2sql-log-list">
              <article v-for="log in logs" :key="log.id" class="nl2sql-log">
                <header>
                  <span class="nl2sql-status" :data-status="log.status">{{ statusLabel(log.status) }}</span>
                  <span class="nl2sql-log-time">{{ dateTime(log.createdAt) }}</span>
                </header>
                <p class="nl2sql-log-question">{{ log.question }}</p>
                <p v-if="log.rejectReason" class="nl2sql-log-reason">{{ log.rejectReason }}</p>
                <p class="nl2sql-log-meta">
                  {{ log.rowCount }} 行 · {{ log.latencyMs }}ms · {{ log.traceId.slice(0, 8) }}
                </p>
              </article>
            </div>
          </aside>
        </div>
      </main>
    </div>
  </div>
</template>
