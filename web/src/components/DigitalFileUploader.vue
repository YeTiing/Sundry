<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { adminApi } from '@/api/admin'
import { getAccessToken } from '@/api/http'
import type { DigitalFile, DigitalPart } from '@/api/types'

const props = withDefaults(
  defineProps<{
    modelValue: number | null
    maxBytes?: number
  }>(),
  { maxBytes: 2 * 1024 * 1024 * 1024 },
)

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  'update:uploading': [value: boolean]
}>()

const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)
const error = ref('')
const uploadedFile = ref<DigitalFile | null>(null)
const uploadedThisSession = ref(false)
const activeUploadId = ref<string | null>(null)
const activeKey = ref<string | null>(null)
const xhrs = new Set<XMLHttpRequest>()
let cancelled = false

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0] ?? null
  error.value = ''
  uploadedFile.value = null
  uploadedThisSession.value = false
  progress.value = 0
  file.value = null

  if (!selected) return
  if (selected.size <= 0) {
    error.value = '文件为空'
    input.value = ''
    return
  }
  if (selected.size > props.maxBytes) {
    error.value = `文件不能超过 ${formatSize(props.maxBytes)}`
    input.value = ''
    return
  }
  file.value = selected
}

function updateProgress(loaded: Map<number, number>, total: number): void {
  let done = 0
  for (const value of loaded.values()) done += value
  progress.value = Math.min(100, Math.round((done / total) * 100))
}

function uploadPart(part: DigitalPart, blob: Blob,
                    loaded: Map<number, number>, total: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhrs.add(xhr)
    xhr.open('PUT', part.url)
    xhr.upload.onprogress = (event) => {
      loaded.set(part.partNumber, Math.min(event.loaded, blob.size))
      updateProgress(loaded, total)
    }
    xhr.onload = () => {
      xhrs.delete(xhr)
      if (xhr.status >= 200 && xhr.status < 300) {
        loaded.set(part.partNumber, blob.size)
        updateProgress(loaded, total)
        resolve()
      } else {
        reject(new Error(`分片 ${part.partNumber} 上传失败（HTTP ${xhr.status}）`))
      }
    }
    xhr.onerror = () => {
      xhrs.delete(xhr)
      reject(new Error('分片上传网络错误'))
    }
    xhr.onabort = () => {
      xhrs.delete(xhr)
      reject(new Error('已取消'))
    }
    xhr.send(blob)
  })
}

async function startUpload(): Promise<void> {
  if (!file.value || uploading.value) return
  const selected = file.value
  uploading.value = true
  progress.value = 0
  error.value = ''
  cancelled = false
  emit('update:uploading', true)

  try {
    const init = await adminApi.initDigitalUpload({
      fileName: selected.name,
      contentType: selected.type || 'application/octet-stream',
      sizeBytes: selected.size,
    })
    activeUploadId.value = init.uploadId
    activeKey.value = init.key

    const loaded = new Map<number, number>()
    let next = 0
    const worker = async (): Promise<void> => {
      while (next < init.parts.length) {
        if (cancelled) throw new Error('已取消')
        const part = init.parts[next++]
        const start = (part.partNumber - 1) * init.partSize
        const end = Math.min(start + init.partSize, selected.size)
        await uploadPart(part, selected.slice(start, end), loaded, selected.size)
      }
    }
    await Promise.all(
      Array.from({ length: Math.min(3, init.parts.length) }, () => worker()),
    )

    const done = await adminApi.completeDigitalUpload({
      uploadId: init.uploadId,
      key: init.key,
    })
    uploadedFile.value = done
    uploadedThisSession.value = true
    progress.value = 100
    emit('update:modelValue', done.id)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '上传失败'
    if (activeUploadId.value && activeKey.value) {
      try {
        await adminApi.abortDigitalUpload({
          uploadId: activeUploadId.value,
          key: activeKey.value,
        })
      } catch {
        // 取消失败不覆盖原始错误
      }
    }
    emit('update:modelValue', null)
  } finally {
    uploading.value = false
    emit('update:uploading', false)
    activeUploadId.value = null
    activeKey.value = null
  }
}

async function cancel(): Promise<void> {
  cancelled = true
  for (const xhr of xhrs) xhr.abort()
  xhrs.clear()
  if (activeUploadId.value && activeKey.value) {
    try {
      await adminApi.abortDigitalUpload({
        uploadId: activeUploadId.value,
        key: activeKey.value,
      })
    } catch {
      // 忽略
    }
  }
  uploading.value = false
  emit('update:uploading', false)
  progress.value = 0
  error.value = '已取消上传'
}

async function remove(): Promise<void> {
  if (uploading.value) {
    await cancel()
  }
  // 本次会话刚上传、还未保存到商品的文件：直接删对象和数据库记录
  if (uploadedThisSession.value && props.modelValue !== null) {
    try {
      await adminApi.deleteDigitalFile(props.modelValue)
    } catch (err) {
      error.value = err instanceof Error ? err.message : '删除文件失败'
      return
    }
  }
  file.value = null
  uploadedFile.value = null
  uploadedThisSession.value = false
  progress.value = 0
  error.value = ''
  emit('update:modelValue', null)
}

function abortOnServer(): void {
  if (!activeUploadId.value || !activeKey.value) return
  const token = getAccessToken()
  void fetch('/api/admin/uploads/digital/abort', {
    method: 'POST',
    keepalive: true,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({
      uploadId: activeUploadId.value,
      key: activeKey.value,
    }),
  }).catch(() => {
    // 页面关闭阶段失败无法提示
  })
}

function handlePageHide(): void {
  if (!uploading.value) return
  for (const xhr of xhrs) xhr.abort()
  xhrs.clear()
  abortOnServer()
}

watch(
  () => props.modelValue,
  (value) => {
    if (value === null) {
      file.value = null
      uploadedFile.value = null
      uploadedThisSession.value = false
      progress.value = 0
    }
  },
)

onMounted(() => {
  window.addEventListener('pagehide', handlePageHide)
})

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', handlePageHide)
  for (const xhr of xhrs) xhr.abort()
  xhrs.clear()
  if (uploading.value) {
    abortOnServer()
  }
})
</script>

<template>
  <div class="digital-uploader">
    <div class="digital-uploader__row">
      <label class="secondary-button upload-label">
        {{ uploading ? '上传中…' : file ? '重新选择' : '选择文件' }}
        <input type="file" :disabled="uploading" @change="onFileChange" />
      </label>
      <button
        v-if="file && !uploading"
        class="secondary-button"
        type="button"
        @click="startUpload"
      >
        开始上传
      </button>
      <button v-if="uploading" class="secondary-button" type="button" @click="cancel">
        取消上传
      </button>
      <button
        v-if="modelValue && !uploading"
        class="secondary-button"
        type="button"
        @click="remove"
      >
        移除文件
      </button>
    </div>

    <p class="panel-note">最大 2GB，分片直传 MinIO 私有桶，上传完成后才能保存商品。</p>
    <p v-if="file" class="digital-uploader__file">
      {{ file.name }} · {{ formatSize(file.size) }}
    </p>
    <div v-if="uploading || progress > 0" class="upload-progress">
      <span :style="{ width: `${progress}%` }"></span>
    </div>
    <p v-if="uploading" class="panel-note">已上传 {{ progress }}%</p>
    <p v-if="uploadedFile" class="notice">
      已上传：{{ uploadedFile.originalName }} · {{ formatSize(uploadedFile.sizeBytes) }}
    </p>
    <p v-else-if="modelValue" class="panel-note">已绑定文件 #{{ modelValue }}</p>
    <p v-if="error" class="form-error">{{ error }}</p>
  </div>
</template>
