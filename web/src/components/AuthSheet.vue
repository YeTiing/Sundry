<script setup lang="ts">
defineProps<{
  title: string
  lede: string
}>()
</script>

<template>
  <div class="page">
    <section class="sheet">
      <aside class="sheet__stub">
        <p class="brand">Sundry</p>
        <p class="stub-note">自营实物。自己打包，自己发货。</p>
      </aside>

      <div class="sheet__body">
        <header class="sheet__head">
          <h1 class="title">{{ title }}</h1>
          <p class="lede">{{ lede }}</p>
        </header>
        <slot />
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
}

.sheet {
  width: 100%;
  max-width: 860px;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  background: var(--sheet);
  border: 1px solid var(--rule);
  animation: sheet-in 0.45s cubic-bezier(0.16, 1, 0.3, 1) both;
}

.sheet__stub {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 32px;
  padding: 34px 26px;
  background: #f5f4ee;
  border-right: 1px dashed var(--rule-strong);
}

.brand {
  margin: 0;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.045em;
  line-height: 1;
}

.stub-note {
  margin: 0;
  max-width: 11em;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.sheet__body {
  padding: 40px 44px 44px;
}

.sheet__head {
  margin-bottom: 30px;
}

.title {
  margin: 0 0 8px;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.2;
}

.lede {
  margin: 0;
  max-width: 40ch;
  font-size: 14px;
  line-height: 1.7;
  color: var(--muted);
}

@keyframes sheet-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 720px) {
  .page {
    padding: 16px;
    place-items: start center;
  }

  .sheet {
    grid-template-columns: 1fr;
  }

  .sheet__stub {
    flex-direction: row;
    align-items: baseline;
    justify-content: space-between;
    gap: 16px;
    padding: 20px 22px;
    border-right: none;
    border-bottom: 1px dashed var(--rule-strong);
  }

  .brand {
    font-size: 22px;
  }

  .stub-note {
    max-width: none;
    text-align: right;
  }

  .sheet__body {
    padding: 28px 22px 32px;
  }

  .title {
    font-size: 26px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .sheet {
    animation: none;
  }
}
</style>
