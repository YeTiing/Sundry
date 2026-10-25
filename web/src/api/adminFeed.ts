import { http } from './http'

export interface FeedAlgorithmMetric {
  algorithm: string
  exposures: number
  clicks: number
  ctr: number
}

export interface FeedPositionMetric {
  position: number
  exposures: number
  clicks: number
  ctr: number
}

export interface FeedMetrics {
  windowDays: number
  exposures: number
  clicks: number
  ctr: number
  byAlgorithm: FeedAlgorithmMetric[]
  byPosition: FeedPositionMetric[]
  ndcg: {
    overall: number
    ai: number
    rule: number
    tracesWithClicks: number
    k: number
  }
  coverage: {
    exposedProducts: number
    onSaleProducts: number
    rate: number
  }
  diversity: {
    avgCategoriesPerTrace: number
    traces: number
  }
}

export const adminFeedApi = {
  metrics(days: number): Promise<FeedMetrics> {
    return http.get<FeedMetrics>(`/admin/feed/metrics?days=${days}`)
  },
}
