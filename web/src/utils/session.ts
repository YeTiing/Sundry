const SESSION_PREFIX = 'sundry'

export function getOrCreateSessionId(feature: string): string {
  const key = `${SESSION_PREFIX}.${feature}.sessionId`
  try {
    const existing = sessionStorage.getItem(key)
    if (existing) return existing
    const created = newSessionId()
    sessionStorage.setItem(key, created)
    return created
  } catch {
    // 隐私模式禁用 storage 时退化为当前页面会话
    return newSessionId()
  }
}

function newSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
