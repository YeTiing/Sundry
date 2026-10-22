<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthSheet from '@/components/AuthSheet.vue'
import SmsCodeButton from '@/components/SmsCodeButton.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { CODE_PATTERN, PASSWORD_PATTERN, isPhone } from '@/utils/validation'

const auth = useAuthStore()
const router = useRouter()

const phone = ref('')
const code = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const submitting = ref(false)

function validate(): string {
  if (!isPhone(phone.value)) return '请输入 11 位手机号'
  if (!CODE_PATTERN.test(code.value)) return '请输入 6 位验证码'
  if (!PASSWORD_PATTERN.test(password.value)) return '密码需 8-64 位，且同时包含字母和数字'
  if (password.value !== confirmPassword.value) return '两次输入的密码不一致'
  return ''
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = validate()
  if (error.value) return

  submitting.value = true
  try {
    await auth.register(phone.value, code.value, password.value)
    await router.replace('/')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '注册失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthSheet title="注册" lede="先验证手机号，再设置密码。密码经哈希后保存。">
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

      <div class="field">
        <label class="field__label" for="register-code">验证码</label>
        <div class="field__row">
          <input
            id="register-code"
            v-model.trim="code"
            inputmode="numeric"
            maxlength="6"
            autocomplete="one-time-code"
            placeholder="6 位数字"
          />
          <SmsCodeButton :phone="phone" scene="REGISTER" @error="error = $event" />
        </div>
      </div>

      <label class="field">
        <span class="field__label">密码</span>
        <input
          v-model="password"
          type="password"
          autocomplete="new-password"
          placeholder="设置登录密码"
        />
        <span class="field__hint">8-64 位，需同时包含字母和数字</span>
      </label>

      <label class="field">
        <span class="field__label">确认密码</span>
        <input
          v-model="confirmPassword"
          type="password"
          autocomplete="new-password"
          placeholder="再输入一次"
        />
      </label>

      <p v-if="error" class="form-error" role="alert">{{ error }}</p>

      <button class="primary-button" type="submit" :disabled="submitting">
        {{ submitting ? '创建中…' : '创建账号' }}
      </button>
    </form>

    <p class="form-foot">
      <span>已经有账号？</span>
      <RouterLink to="/login">登录</RouterLink>
    </p>
  </AuthSheet>
</template>
