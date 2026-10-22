import type { ApiErrorBody } from './types'

export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

let accessToken: string | null = null
let refreshHandler: (() => Promise<boolean>) | null = null
let refreshInFlight: Promise<boolean> | null = null

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function setRefreshHandler(handler: (() => Promise<boolean>) | null): void {
  refreshHandler = handler
}

function refreshOnce(): Promise<boolean> {
  if (!refreshHandler) {
    return Promise.resolve(false)
  }
  if (!refreshInFlight) {
    refreshInFlight = refreshHandler().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

async function request<T>(path: string, init: RequestInit, allowRetry: boolean): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      headers,
      credentials: 'same-origin',
    })
  } catch {
    throw new ApiError('NETWORK_ERROR', '网络连接失败，请检查网络后重试', 0)
  }

  if (response.status === 401 && allowRetry) {
    const refreshed = await refreshOnce()
    if (refreshed) {
      return request<T>(path, init, false)
    }
  }

  if (!response.ok) {
    let code = 'UNKNOWN'
    let message = '请求失败，请稍后重试'
    try {
      const body = (await response.json()) as Partial<ApiErrorBody>
      if (typeof body.code === 'string') code = body.code
      if (typeof body.message === 'string') message = body.message
    } catch {
      // 非 JSON 响应时保留默认提示
    }
    throw new ApiError(code, message, response.status)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const http = {
  get<T>(path: string): Promise<T> {
    return request<T>(path, { method: 'GET' }, true)
  },
  post<T>(path: string, body?: unknown): Promise<T> {
    return request<T>(
      path,
      {
        method: 'POST',
        body: body === undefined ? undefined : JSON.stringify(body),
      },
      true,
    )
  },
}
