<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthSheet from '@/components/AuthSheet.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const leaving = ref(false)

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
  <AuthSheet title="账户" lede="登录成功。商城与订单页面后续再做，这里先放账号信息。">
    <dl class="account-list">
      <div class="account-row">
        <dt>手机号</dt>
        <dd>{{ auth.user?.phone }}</dd>
      </div>
      <div class="account-row">
        <dt>用户编号</dt>
        <dd>{{ auth.user?.id }}</dd>
      </div>
    </dl>

    <button class="quiet-button" type="button" :disabled="leaving" @click="logout">
      {{ leaving ? '退出中…' : '退出登录' }}
    </button>
  </AuthSheet>
</template>
