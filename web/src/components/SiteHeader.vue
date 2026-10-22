<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <header class="site-head">
    <RouterLink class="site-brand" to="/">Sundry</RouterLink>
    <nav class="site-nav">
      <RouterLink to="/">商品</RouterLink>
      <RouterLink v-if="auth.isAuthenticated" to="/account">账户</RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin/products">管理后台</RouterLink>
      <RouterLink v-if="!auth.isAuthenticated" to="/login">登录</RouterLink>
      <button v-else class="link-button" type="button" @click="logout">退出</button>
    </nav>
  </header>
</template>
