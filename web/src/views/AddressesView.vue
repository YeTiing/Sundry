<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SiteHeader from '@/components/SiteHeader.vue'
import { addressApi } from '@/api/addresses'
import type { Address, AddressPayload } from '@/api/types'

const addresses = ref<Address[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const saving = ref(false)
const busyId = ref<number | null>(null)

const editingId = ref<number | null>(null)
const receiverName = ref('')
const receiverPhone = ref('')
const province = ref('')
const city = ref('')
const district = ref('')
const detail = ref('')
const isDefault = ref(false)

const isEditing = computed(() => editingId.value !== null)

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

function resetForm(): void {
  editingId.value = null
  receiverName.value = ''
  receiverPhone.value = ''
  province.value = ''
  city.value = ''
  district.value = ''
  detail.value = ''
  isDefault.value = addresses.value.length === 0
  error.value = ''
}

async function load(): Promise<void> {
  loading.value = true
  try {
    addresses.value = await addressApi.list()
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function startEdit(address: Address): void {
  editingId.value = address.id
  receiverName.value = address.receiverName
  receiverPhone.value = address.receiverPhone
  province.value = address.province
  city.value = address.city
  district.value = address.district
  detail.value = address.detail
  isDefault.value = address.isDefault
  notice.value = ''
  error.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function validate(): string {
  if (!receiverName.value.trim()) return '收货人不能为空'
  if (!receiverPhone.value.trim()) return '收货电话不能为空'
  if (!province.value.trim() || !city.value.trim() || !district.value.trim()) {
    return '省、市、区县都不能为空'
  }
  if (!detail.value.trim()) return '详细地址不能为空'
  return ''
}

async function save(): Promise<void> {
  if (saving.value) return
  error.value = validate()
  if (error.value) return

  const payload: AddressPayload = {
    receiverName: receiverName.value.trim(),
    receiverPhone: receiverPhone.value.trim(),
    province: province.value.trim(),
    city: city.value.trim(),
    district: district.value.trim(),
    detail: detail.value.trim(),
    isDefault: isDefault.value,
  }

  saving.value = true
  notice.value = ''
  try {
    if (isEditing.value) {
      await addressApi.update(editingId.value!, payload)
      notice.value = '地址已更新'
    } else {
      await addressApi.create(payload)
      notice.value = '地址已添加'
    }
    await load()
    resetForm()
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    saving.value = false
  }
}

async function remove(address: Address): Promise<void> {
  if (!window.confirm(`删除收货地址「${address.receiverName} ${address.detail}」？`)) return
  if (busyId.value !== null) return
  busyId.value = address.id
  error.value = ''
  notice.value = ''
  try {
    await addressApi.remove(address.id)
    if (editingId.value === address.id) resetForm()
    await load()
    notice.value = '地址已删除'
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    busyId.value = null
  }
}

async function setDefault(address: Address): Promise<void> {
  if (busyId.value !== null || address.isDefault) return
  busyId.value = address.id
  error.value = ''
  notice.value = ''
  try {
    await addressApi.setDefault(address.id)
    await load()
    notice.value = '已设为默认地址'
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    busyId.value = null
  }
}

onMounted(async () => {
  await load()
  resetForm()
})
</script>

<template>
  <div class="store-page">
    <SiteHeader />

    <main class="store-main">
      <RouterLink class="back-link" to="/cart">← 返回购物车</RouterLink>

      <header class="page-head">
        <div>
          <p class="store-kicker">Sundry / 收货地址</p>
          <h1 class="page-title">收货地址</h1>
        </div>
        <span class="panel-note">最多 20 条，第一条自动设为默认</span>
      </header>

      <section class="admin-panel">
        <div class="panel-head">
          <div>
            <h2>{{ isEditing ? '编辑地址' : '新增地址' }}</h2>
            <p class="panel-note">实物商品下单前必须选择收货地址。</p>
          </div>
          <button v-if="isEditing" class="secondary-button" type="button" @click="resetForm">
            取消编辑
          </button>
        </div>

        <p v-if="error" class="form-error">{{ error }}</p>
        <p v-if="notice" class="notice">{{ notice }}</p>

        <form class="admin-form" novalidate @submit.prevent="save">
          <div class="admin-form__grid">
            <label class="field">
              <span class="field__label">收货人</span>
              <input v-model.trim="receiverName" type="text" maxlength="50" placeholder="姓名" />
            </label>

            <label class="field">
              <span class="field__label">收货电话</span>
              <input
                v-model.trim="receiverPhone"
                type="tel"
                maxlength="20"
                placeholder="手机号或固话"
              />
            </label>

            <label class="field">
              <span class="field__label">省份</span>
              <input v-model.trim="province" type="text" maxlength="50" placeholder="浙江省" />
            </label>

            <label class="field">
              <span class="field__label">城市</span>
              <input v-model.trim="city" type="text" maxlength="50" placeholder="杭州市" />
            </label>

            <label class="field">
              <span class="field__label">区县</span>
              <input v-model.trim="district" type="text" maxlength="50" placeholder="西湖区" />
            </label>

            <label class="field">
              <span class="field__label">详细地址</span>
              <input
                v-model.trim="detail"
                type="text"
                maxlength="200"
                placeholder="街道、门牌号、楼栋"
              />
            </label>
          </div>

          <label class="checkbox-field">
            <input v-model="isDefault" type="checkbox" />
            <span>设为默认地址</span>
          </label>

          <div class="admin-actions">
            <button class="primary-button admin-action" type="submit" :disabled="saving">
              {{ saving ? '保存中…' : isEditing ? '保存修改' : '新增地址' }}
            </button>
          </div>
        </form>
      </section>

      <section class="admin-panel address-panel">
        <div class="panel-head">
          <div>
            <h2>地址簿</h2>
            <p class="panel-note">共 {{ addresses.length }} 条</p>
          </div>
        </div>

        <p v-if="loading" class="panel-note">加载中…</p>
        <p v-else-if="addresses.length === 0" class="panel-note">还没有收货地址。</p>

        <div v-else class="address-list">
          <article v-for="address in addresses" :key="address.id" class="address-card">
            <div class="address-card__body">
              <p class="address-card__head">
                <strong>{{ address.receiverName }}</strong>
                <span class="mono">{{ address.receiverPhone }}</span>
                <span v-if="address.isDefault" class="status-pill" data-status="ON_SALE">
                  默认
                </span>
              </p>
              <p class="address-card__text">
                {{ address.province }}{{ address.city }}{{ address.district }}{{ address.detail }}
              </p>
            </div>
            <div class="row-actions">
              <button
                v-if="!address.isDefault"
                class="secondary-button"
                type="button"
                :disabled="busyId !== null"
                @click="setDefault(address)"
              >
                设为默认
              </button>
              <button class="secondary-button" type="button" @click="startEdit(address)">
                编辑
              </button>
              <button
                class="secondary-button danger-button"
                type="button"
                :disabled="busyId !== null"
                @click="remove(address)"
              >
                删除
              </button>
            </div>
          </article>
        </div>
      </section>
    </main>
  </div>
</template>
