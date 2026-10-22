<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { authApi } from '@/api/auth'
import { ApiError } from '@/api/http'
import type { SmsScene } from '@/api/types'
import { isPhone } from '@/utils/validation'

const props = defineProps<{
  phone: string
  scene: SmsScene
}>()

const emit = defineEmits<{
  (event: 'error', message: string): void
  (event: 'sent'): void
}>()

const seconds = ref(0)
const sending = ref(false)
let timer: number | undefined

const enabled = computed(() => seconds.value === 0 && !sending.value && isPhone(props.phone))

function stopTimer(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function startCountdown(): void {
  seconds.value = 60
  stopTimer()
  timer = window.setInterval(() => {
    seconds.value -= 1
    if (seconds.value <= 0) {
      stopTimer()
    }
  }, 1000)
}

async function send(): Promise<void> {
  if (!enabled.value) return
  sending.value = true
  try {
    await authApi.sendCode(props.phone, props.scene)
    startCountdown()
    emit('sent')
  } catch (error) {
    emit('error', error instanceof ApiError ? error.message : '验证码发送失败，请稍后重试')
  } finally {
    sending.value = false
  }
}

onBeforeUnmount(stopTimer)
</script>

<template>
  <button type="button" class="sms-button" :disabled="!enabled" @click="send">
    {{ seconds > 0 ? `${seconds} 秒后重发` : sending ? '发送中…' : '获取验证码' }}
  </button>
</template>

<style scoped>
.sms-button {
  min-width: 118px;
  padding: 10px 14px;
  border: 1px solid #c9d4e6;
  background: var(--blue-wash);
  color: var(--blue);
  font-family: inherit;
  font-size: 13px;
  line-height: 1.3;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.sms-button:hover:not(:disabled) {
  background: #e2e9f4;
  border-color: var(--blue);
}

.sms-button:disabled {
  background: #f1f0eb;
  border-color: var(--rule);
  color: var(--muted);
  cursor: not-allowed;
}
</style>
