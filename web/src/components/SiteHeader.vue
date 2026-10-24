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
      <RouterLink to="/">推荐</RouterLink>
      <RouterLink to="/products">商品</RouterLink>
      <RouterLink v-if="auth.isAuthenticated && !auth.isAdmin" to="/cart">购物车</RouterLink>
      <RouterLink v-if="auth.isAuthenticated && !auth.isAdmin" to="/orders">我的订单</RouterLink>
      <RouterLink v-if="auth.isAuthenticated" class="site-user" to="/account">
        <img
          v-if="auth.user?.avatarUrl"
          class="site-user__avatar"
          :src="auth.user.avatarUrl"
          alt=""
        />
        <span v-else class="site-user__avatar site-user__avatar--text">
          {{ (auth.user?.nickname || auth.user?.phone || '?').slice(0, 1) }}
        </span>
        <span class="site-user__name">{{ auth.user?.nickname || auth.user?.phone }}</span>
      </RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin/products">管理后台</RouterLink>
      <RouterLink v-if="!auth.isAuthenticated" to="/login">登录</RouterLink>
      <button v-else class="link-button" type="button" @click="logout">退出</button>
    </nav>
  </header>
</template>
