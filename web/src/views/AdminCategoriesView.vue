<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import type { AdminCategory } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const categories = ref<AdminCategory[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const saving = ref(false)
const busyId = ref<number | null>(null)

const editingId = ref<number | null>(null)
const name = ref('')
const sortOrderInput = ref('0')
const status = ref(1)

const isEditing = computed(() => editingId.value !== null)

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : '操作失败，请稍后重试'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    categories.value = await adminApi.listCategories()
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  editingId.value = null
  name.value = ''
  sortOrderInput.value = '0'
  status.value = 1
  error.value = ''
}

function startEdit(category: AdminCategory): void {
  editingId.value = category.id
  name.value = category.name
  sortOrderInput.value = String(category.sortOrder)
  status.value = category.status
  error.value = ''
  notice.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function validate(): string {
  if (!name.value.trim()) return '分类名称不能为空'
  if (!/^\d+$/.test(sortOrderInput.value.trim())) return '排序值必须是非负整数'
  return ''
}

async function save(): Promise<void> {
  if (saving.value) return
  error.value = validate()
  if (error.value) return

  const payload = {
    name: name.value.trim(),
    sortOrder: Number(sortOrderInput.value),
    status: status.value,
  }

  saving.value = true
  notice.value = ''
  try {
    if (isEditing.value) {
      await adminApi.updateCategory(editingId.value!, payload)
      notice.value = '分类已更新'
    } else {
      await adminApi.createCategory(payload)
      notice.value = '分类已创建'
    }
    resetForm()
    await load()
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    saving.value = false
  }
}

async function act(category: AdminCategory, message: string,
                   action: () => Promise<unknown>): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = category.id
  error.value = ''
  notice.value = ''
  try {
    await action()
    await load()
    notice.value = message
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    busyId.value = null
  }
}

function toggleStatus(category: AdminCategory): void {
  const next = category.status === 1 ? 0 : 1
  void act(category, next === 1 ? '分类已启用' : '分类已停用', () =>
    adminApi.updateCategory(category.id, {
      name: category.name,
      sortOrder: category.sortOrder,
      status: next,
    }))
}

function remove(category: AdminCategory): void {
  if (category.productCount > 0) {
    error.value = `「${category.name}」下还有 ${category.productCount} 个商品，不能删除，请先迁移或停用`
    return
  }
  if (!window.confirm(`删除分类「${category.name}」？`)) return
  void act(category, '分类已删除', () => adminApi.deleteCategory(category.id))
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(async () => {
  await load()
  resetForm()
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-head">
        <div>
          <p class="admin-kicker">Sundry / 管理</p>
          <h1 class="admin-title">分类管理</h1>
        </div>
        <nav class="admin-nav">
          <RouterLink to="/">商城</RouterLink>
          <RouterLink to="/admin/products">商品管理</RouterLink>
          <RouterLink to="/admin/orders">订单管理</RouterLink>
          <RouterLink to="/admin/seckill">秒杀活动</RouterLink>
          <RouterLink to="/admin">图片与用户</RouterLink>
          <button class="link-button" type="button" @click="logout">退出登录</button>
        </nav>
      </header>

      <main class="admin-main">
        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>{{ isEditing ? '编辑分类' : '新增分类' }}</h2>
              <p class="panel-note">
                排序值越小越靠前；停用后前台不再显示，但商品保留原分类。
              </p>
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
                <span class="field__label">分类名称</span>
                <input v-model.trim="name" type="text" maxlength="50" placeholder="例如：设计素材" />
              </label>

              <label class="field">
                <span class="field__label">排序值</span>
                <input v-model.trim="sortOrderInput" type="text" inputmode="numeric" placeholder="0" />
              </label>

              <label class="field">
                <span class="field__label">状态</span>
                <select v-model.number="status">
                  <option :value="1">启用</option>
                  <option :value="0">停用</option>
                </select>
              </label>
            </div>

            <div class="admin-actions">
              <button class="primary-button admin-action" type="submit" :disabled="saving">
                {{ saving ? '保存中…' : isEditing ? '保存修改' : '创建分类' }}
              </button>
            </div>
          </form>
        </section>

        <section class="admin-panel">
          <div class="panel-head">
            <div>
              <h2>分类列表</h2>
              <p class="panel-note">共 {{ categories.length }} 个分类。</p>
            </div>
            <button class="secondary-button" type="button" :disabled="loading" @click="load">
              刷新
            </button>
          </div>

          <p v-if="loading" class="panel-note">加载中…</p>
          <p v-else-if="categories.length === 0" class="panel-note">还没有分类。</p>

          <table v-else class="admin-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>名称</th>
                <th>排序</th>
                <th>商品数</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="category in categories" :key="category.id">
                <td class="mono">{{ category.id }}</td>
                <td>{{ category.name }}</td>
                <td class="mono">{{ category.sortOrder }}</td>
                <td class="mono">{{ category.productCount }}</td>
                <td>
                  <span class="seckill-status" :data-status="category.status === 1 ? 'ACTIVE' : 'CANCELLED'">
                    {{ category.status === 1 ? '启用' : '停用' }}
                  </span>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="secondary-button" type="button" @click="startEdit(category)">
                      编辑
                    </button>
                    <button
                      class="secondary-button"
                      type="button"
                      :disabled="busyId !== null"
                      @click="toggleStatus(category)"
                    >
                      {{ category.status === 1 ? '停用' : '启用' }}
                    </button>
                    <button
                      class="secondary-button danger-button"
                      type="button"
                      :disabled="busyId !== null || category.productCount > 0"
                      :title="category.productCount > 0 ? '分类下还有商品，不能删除' : '删除分类'"
                      @click="remove(category)"
                    >
                      删除
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </section>
      </main>
    </div>
  </div>
</template>
