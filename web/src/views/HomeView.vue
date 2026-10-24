<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthSheet from '@/components/AuthSheet.vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const MAX_AVATAR_BYTES = 2 * 1024 * 1024
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']

const auth = useAuthStore()
const router = useRouter()

const nickname = ref('')
const bio = ref('')
const savingProfile = ref(false)
const uploadingAvatar = ref(false)
const profileError = ref('')
const profileNotice = ref('')
const leaving = ref(false)

const roleLabel = computed(() => {
  const role = auth.user?.role
  return role === 'SUPER_ADMIN' ? '超级管理员' : role === 'ADMIN' ? '管理员' : '普通用户'
})

const avatarLetter = computed(() =>
  (auth.user?.nickname || auth.user?.phone || '?').slice(0, 1),
)

onMounted(() => {
  nickname.value = auth.user?.nickname ?? ''
  bio.value = auth.user?.bio ?? ''
})

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

async function saveProfile(): Promise<void> {
  if (savingProfile.value) return
  savingProfile.value = true
  profileError.value = ''
  profileNotice.value = ''
  try {
    const updated = await authApi.updateProfile({ nickname: nickname.value, bio: bio.value })
    auth.setUser(updated)
    nickname.value = updated.nickname ?? ''
    bio.value = updated.bio ?? ''
    profileNotice.value = '名片已保存'
  } catch (err) {
    profileError.value = messageOf(err)
  } finally {
    savingProfile.value = false
  }
}

async function onAvatarChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!ALLOWED_TYPES.includes(file.type)) {
    profileError.value = '头像只支持 JPEG、PNG、WebP'
    input.value = ''
    return
  }
  if (file.size > MAX_AVATAR_BYTES) {
    profileError.value = '头像不能超过 2MB'
    input.value = ''
    return
  }

  uploadingAvatar.value = true
  profileError.value = ''
  profileNotice.value = ''
  try {
    const updated = await authApi.uploadAvatar(file)
    auth.setUser(updated)
    profileNotice.value = '头像已更新'
  } catch (err) {
    profileError.value = messageOf(err)
  } finally {
    uploadingAvatar.value = false
    input.value = ''
  }
}

async function logout(): Promise<void> {
  if (leaving.value) return
  leaving.value = true
  try {
    await auth.logout()
    await router.replace('/login')
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <AuthSheet title="账户" lede="头像、昵称和简介会显示在账户页；商品详情会展示店铺名片。">
    <div class="profile-head">
      <img
        v-if="auth.user?.avatarUrl"
        class="profile-avatar"
        :src="auth.user.avatarUrl"
        alt="头像"
      />
      <span v-else class="profile-avatar profile-avatar--text">{{ avatarLetter }}</span>
      <div class="profile-head__actions">
        <p class="profile-name">{{ auth.user?.nickname || auth.user?.phone }}</p>
        <label class="secondary-button upload-label">
          {{ uploadingAvatar ? '上传中…' : '更换头像' }}
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp"
            :disabled="uploadingAvatar"
            @change="onAvatarChange"
          />
        </label>
        <p class="panel-note">JPEG / PNG / WebP，≤2MB</p>
      </div>
    </div>

    <form class="profile-form" novalidate @submit.prevent="saveProfile">
      <label class="field">
        <span class="field__label">昵称</span>
        <input v-model.trim="nickname" type="text" maxlength="50" placeholder="给自己起个名字" />
      </label>

      <label class="field">
        <span class="field__label">简介</span>
        <textarea v-model.trim="bio" rows="3" maxlength="200" placeholder="一句话介绍自己" />
      </label>

      <p v-if="profileError" class="form-error" role="alert">{{ profileError }}</p>
      <p v-if="profileNotice" class="notice">{{ profileNotice }}</p>

      <button class="primary-button" type="submit" :disabled="savingProfile">
        {{ savingProfile ? '保存中…' : '保存名片' }}
      </button>
    </form>

    <dl class="account-list">
      <div class="account-row">
        <dt>手机号</dt>
        <dd>{{ auth.user?.phone }}</dd>
      </div>
      <div class="account-row">
        <dt>用户编号</dt>
        <dd>{{ auth.user?.id }}</dd>
      </div>
      <div class="account-row">
        <dt>角色</dt>
        <dd>{{ roleLabel }}</dd>
      </div>
    </dl>

    <template v-if="!auth.isAdmin">
      <RouterLink class="secondary-button home-admin-link" to="/cart">我的购物车</RouterLink>

      <RouterLink class="secondary-button home-admin-link" to="/orders">我的订单</RouterLink>

      <RouterLink class="secondary-button home-admin-link" to="/addresses">收货地址</RouterLink>
    </template>

    <RouterLink
      v-if="auth.isSuperAdmin"
      class="secondary-button home-admin-link"
      to="/admin"
      title="在管理后台的店铺资料里修改"
    >
      店铺发货地址
    </RouterLink>

    <RouterLink class="quiet-button home-admin-link" to="/">返回商城</RouterLink>

    <RouterLink v-if="auth.isAdmin" class="primary-button home-admin-link" to="/admin">
      进入管理后台
    </RouterLink>

    <button class="quiet-button" type="button" :disabled="leaving" @click="logout">
      {{ leaving ? '退出中…' : '退出登录' }}
    </button>
  </AuthSheet>
</template>
