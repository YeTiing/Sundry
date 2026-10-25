import { http } from './http'
import type { FeedEventPayload, FeedPage, FeedStatus, FeaturedSeckill } from './types'

export const feedApi = {
  getFeed(
    cursor: number | null,
    size: number,
    refresh = false,
    buildId: string | null = null,
  ): Promise<FeedPage> {
    const params = new URLSearchParams({ size: String(size) })
    if (cursor !== null) {
      params.set('cursor', String(cursor))
    }
    if (refresh) {
      params.set('refresh', 'true')
    }
    if (buildId) {
      params.set('buildId', buildId)
    }
    return http.get<FeedPage>(`/feed?${params.toString()}`)
  },

  getStatus(): Promise<FeedStatus> {
    return http.get<FeedStatus>('/feed/status')
  },

  getFeatured(): Promise<FeaturedSeckill[]> {
    return http.get<FeaturedSeckill[]>('/feed/featured')
  },

  recordEvents(payload: FeedEventPayload, keepalive = false): Promise<void> {
    return keepalive
      ? http.postKeepalive<void>('/feed/events', payload)
      : http.post<void>('/feed/events', payload)
  },
}

export const behaviorApi = {
  recordView(productId: number): Promise<void> {
    return http.post<void>('/behaviors/view', { productId })
  },
}
