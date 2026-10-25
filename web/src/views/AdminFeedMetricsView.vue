<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminFeedApi } from '@/api/adminFeed'
import type { FeedMetrics } from '@/api/adminFeed'
import { useAuthStore } from '@/stores/auth'

const WINDOWS = [7, 14, 30]

const auth = useAuthStore()
const router = useRouter()

const days = ref(7)
const metrics = ref<FeedMetrics | null>(null)
const loading = ref(true)
const error = ref('')

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '指标加载失败'
}

function pct(value: number): string {
  return `${(value * 100).toFixed(2)}%`
}

function number(value: number): string {
  return Number(value).toLocaleString('zh-CN')
}

function fixed(value: number, digits = 4): string {
  return Number(value).toFixed(digits)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    metrics.value = await adminFeedApi.metrics(days.value)
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function selectWindow(value: number): void {
  if (days.value === value) return
  days.value = value
  void load()
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">推荐指标</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin/products">商品管理</RouterLink>
          <RouterLink to="/admin/categories">分类管理</RouterLink>
          <RouterLink to="/admin/orders">订单管理</RouterLink>
          <RouterLink to="/admin/seckill">秒杀活动</RouterLink>
          <RouterLink to="/admin/feed">推荐指标</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <section class="admin-panel">
          <div class="panel-head">
            <h2>曝光与点击</h2>
            <div class="panel-head-actions">
              <button
                v-for="window in WINDOWS"
                :key="window"
                class="secondary-button"
                type="button"
                :disabled="loading || days === window"
                @click="selectWindow(window)"
              >
                {{ days === window ? `近 ${window} 天` : `${window} 天` }}
              </button>
            </div>
          </div>
          <p class="panel-note">
            CTR 的分母是曝光；NDCG 只统计有点击的 trace。合成数据仅用于验证口径，不代表真实效果。
          </p>

          <p v-if="error" class="form-error">{{ error }}</p>
          <p v-if="loading" class="panel-note">加载中…</p>

          <template v-else-if="metrics">
            <div class="metric-grid">
              <div class="metric-card">
                <div class="metric-card__label">曝光</div>
                <div class="metric-card__value">{{ number(metrics.exposures) }}</div>
                <div class="metric-card__hint">{{ metrics.diversity.traces }} 次列表渲染</div>
              </div>
              <div class="metric-card">
                <div class="metric-card__label">点击</div>
                <div class="metric-card__value">{{ number(metrics.clicks) }}</div>
                <div class="metric-card__hint">CTR {{ pct(metrics.ctr) }}</div>
              </div>
              <div class="metric-card">
                <div class="metric-card__label">NDCG@{{ metrics.ndcg.k }}</div>
                <div class="metric-card__value">{{ fixed(metrics.ndcg.overall) }}</div>
                <div class="metric-card__hint">
                  AI {{ fixed(metrics.ndcg.ai) }} · RULE {{ fixed(metrics.ndcg.rule) }}
                </div>
              </div>
              <div class="metric-card">
                <div class="metric-card__label">覆盖率</div>
                <div class="metric-card__value">{{ pct(metrics.coverage.rate) }}</div>
                <div class="metric-card__hint">
                  {{ number(metrics.coverage.exposedProducts) }} /
                  {{ number(metrics.coverage.onSaleProducts) }} 在售商品
                </div>
              </div>
              <div class="metric-card">
                <div class="metric-card__label">多样性</div>
                <div class="metric-card__value">{{ fixed(metrics.diversity.avgCategoriesPerTrace, 2) }}</div>
                <div class="metric-card__hint">平均每页分类数</div>
              </div>
            </div>

            <h2 class="metric-section">按算法</h2>
            <table class="admin-table">
              <thead>
                <tr>
                  <th>算法</th>
                  <th>曝光</th>
                  <th>点击</th>
                  <th>CTR</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in metrics.byAlgorithm" :key="row.algorithm">
                  <td>{{ row.algorithm }}</td>
                  <td>{{ number(row.exposures) }}</td>
                  <td>{{ number(row.clicks) }}</td>
                  <td>{{ pct(row.ctr) }}</td>
                </tr>
                <tr v-if="metrics.byAlgorithm.length === 0">
                  <td colspan="4">暂无数据</td>
                </tr>
              </tbody>
            </table>

            <h2 class="metric-section">按位置</h2>
            <table class="admin-table">
              <thead>
                <tr>
                  <th>位置</th>
                  <th>曝光</th>
                  <th>点击</th>
                  <th>CTR</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in metrics.byPosition" :key="row.position">
                  <td>#{{ row.position }}</td>
                  <td>{{ number(row.exposures) }}</td>
                  <td>{{ number(row.clicks) }}</td>
                  <td>{{ pct(row.ctr) }}</td>
                </tr>
                <tr v-if="metrics.byPosition.length === 0">
                  <td colspan="4">暂无数据</td>
                </tr>
              </tbody>
            </table>
          </template>
        </section>
      </main>
    </div>
  </div>
</template>
