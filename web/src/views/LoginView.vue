<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthSheet from '@/components/AuthSheet.vue'
import SmsCodeButton from '@/components/SmsCodeButton.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { CODE_PATTERN, isPhone } from '@/utils/validation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const mode = ref<'code' | 'password'>('code')
const phone = ref('')
const code = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)

function switchMode(next: 'code' | 'password'): void {
  mode.value = next
  error.value = ''
}

function validate(): string {
  if (!isPhone(phone.value)) return '请输入 11 位手机号'
  if (mode.value === 'code' && !CODE_PATTERN.test(code.value)) return '请输入 6 位验证码'
  if (mode.value === 'password' && password.value.length === 0) return '请输入密码'
  return ''
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = validate()
  if (error.value) return

  submitting.value = true
  try {
    if (mode.value === 'code') {
      await auth.loginByCode(phone.value, code.value)
    } else {
      await auth.loginByPassword(phone.value, password.value)
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthSheet title="登录" lede="用手机号收验证码，或者改用密码登录。">
    <div class="mode-switch" role="group" aria-label="登录方式">
      <button
        type="button"
        class="mode-switch__item"
        :class="{ 'is-active': mode === 'code' }"
        :aria-pressed="mode === 'code'"
        @click="switchMode('code')"
      >
        验证码
      </button>
      <button
        type="button"
        class="mode-switch__item"
        :class="{ 'is-active': mode === 'password' }"
        :aria-pressed="mode === 'password'"
        @click="switchMode('password')"
      >
        密码
      </button>
    </div>

    <form novalidate @submit.prevent="submit">
      <label class="field">
        <span class="field__label">手机号</span>
        <input
          v-model.trim="phone"
          type="tel"
          inputmode="numeric"
          maxlength="11"
          autocomplete="tel"
          placeholder="11 位手机号"
        />
      </label>

      <div v-show="mode === 'code'" class="field">
        <label class="field__label" for="login-code">验证码</label>
        <div class="field__row">
          <input
            id="login-code"
            v-model.trim="code"
            inputmode="numeric"
            maxlength="6"
            autocomplete="one-time-code"
            placeholder="6 位数字"
          />
          <SmsCodeButton :phone="phone" scene="LOGIN" @error="error = $event" />
        </div>
      </div>

      <label v-show="mode === 'password'" class="field">
        <span class="field__label">密码</span>
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </label>

      <p v-if="error" class="form-error" role="alert">{{ error }}</p>

      <button class="primary-button" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>

    <p class="form-foot">
      <span>还没有账号？</span>
      <RouterLink to="/register">注册</RouterLink>
    </p>
  </AuthSheet>
</template>
