<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SiteHeader from '@/components/SiteHeader.vue'
import { addressApi } from '@/api/addresses'
import { seckillApi } from '@/api/seckill'
import type { Address, SeckillActivity } from '@/api/types'

const route = useRoute()
const router = useRouter()

const activity = ref<SeckillActivity | null>(null)
const addresses = ref<Address[]>([])
const addressId = ref<number | null>(null)
const loading = ref(true)
const error = ref('')
const submitting = ref(false)

const physical = computed(() => activity.value?.productType === 'PHYSICAL')
const soldOut = computed(() => activity.value !== null && activity.value.remaining <= 0)
const canSubmit = computed(() =>
  !submitting.value
  && activity.value !== null
  && !soldOut.value
  && (!physical.value || addressId.value !== null),
)

function formatPrice(value: number): string {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

function addressText(address: Address): string {
  return `${address.province}${address.city}${address.district}${address.detail}`
}

async function load(): Promise<void> {
  const id = Number(route.params.activityId)
  if (!Number.isInteger(id) || id <= 0) {
    error.value = '活动不存在'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    activity.value = await seckillApi.activity(id)
    if (activity.value.productType === 'PHYSICAL') {
      addresses.value = await addressApi.list()
      const preferred = addresses.value.find((address) => address.isDefault) ?? addresses.value[0]
      addressId.value = preferred?.id ?? null
    }
  } catch (err) {
    activity.value = null
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  if (!activity.value || !canSubmit.value) return
  submitting.value = true
  error.value = ''
  try {
    await seckillApi.createOrder(
      activity.value.id,
      physical.value ? addressId.value : null,
    )
    await router.push({ name: 'orders' })
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <RouterLink class="back-link" to="/">← 返回商品列表</RouterLink>

      <p v-if="loading" class="store-empty">加载中…</p>
      <p v-else-if="error" class="form-error">{{ error }}</p>

      <template v-else-if="activity">
        <section class="seckill-hero">
          <div>
            <p class="store-kicker">Sundry / 秒杀</p>
            <h1 class="page-title">{{ activity.productName }}</h1>
            <p class="seckill-price seckill-hero__price">
              {{ formatPrice(activity.seckillPrice) }}
              <span class="price-original">{{ formatPrice(activity.productPrice) }}</span>
            </p>
            <p class="seckill-note">
              每人限购 {{ activity.perUserLimit }} 件 · 剩余 {{ activity.remaining }} 件
              · 活动至 {{ formatDate(activity.endAt) }}
            </p>
          </div>
          <span class="seckill-badge seckill-hero__badge">秒杀进行中</span>
        </section>

        <section v-if="physical" class="admin-panel checkout-section">
          <div class="panel-head">
            <div>
              <h2>收货地址</h2>
              <p class="panel-note">实物商品必须选择一个收货地址。</p>
            </div>
          </div>

          <p v-if="addresses.length === 0" class="panel-note">
            还没有收货地址，<RouterLink to="/addresses">去添加</RouterLink>。
          </p>

          <div v-else class="address-list">
            <label
              v-for="address in addresses"
              :key="address.id"
              class="address-card address-card--select"
              :class="{ 'is-selected': addressId === address.id }"
            >
              <input v-model="addressId" type="radio" name="address" :value="address.id" />
              <div class="address-card__body">
                <p class="address-card__head">
                  <strong>{{ address.receiverName }}</strong>
                  <span class="mono">{{ address.receiverPhone }}</span>
                  <span v-if="address.isDefault" class="status-pill" data-status="ON_SALE">
                    默认
                  </span>
                </p>
                <p class="address-card__text">{{ addressText(address) }}</p>
              </div>
            </label>
          </div>
        </section>

        <section v-else class="admin-panel checkout-section">
          <p class="panel-note">数字商品无需收货地址，支付后可在订单页下载。</p>
        </section>

        <footer class="cart-summary admin-panel">
          <div>
            <p class="panel-note">
              {{ soldOut ? '本场秒杀已售罄' : '数量固定 1 件' }}
            </p>
            <p class="cart-summary__total mono">应付 {{ formatPrice(activity.seckillPrice) }}</p>
          </div>
          <button
            class="primary-button cart-summary__action"
            type="button"
            :disabled="!canSubmit"
            @click="submit"
          >
            {{ soldOut ? '已售罄' : submitting ? '抢购中…' : '立即抢购' }}
          </button>
        </footer>
      </template>
    </main>
  </div>
</template>
