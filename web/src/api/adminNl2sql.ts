import { http } from './http'
import type { PageResponse } from './types'

export interface Nl2sqlVocabulary {
  metrics: { key: string; label: string; synonyms: string[]; unit: string | null }[]
  dimensions: { key: string; label: string; synonyms: string[]; granularity: string[] }[]
  filters: { key: string; label: string; type: string; values: string[] }[]
  timePresets: string[]
  limits: {
    maxMetrics: number
    maxDimensions: number
    maxRangeDays: number
    maxRows: number
  }
}

export interface Nl2sqlQueryResult {
  answer: string
  columns: { key: string; label: string; unit: string | null }[]
  rows: Record<string, unknown>[]
  plan: {
    metrics: string[]
    dimensions: string[]
    filters: { key: string; value: unknown }[]
    time: { preset: string | null; start: string; end: string }
    granularity: string
    limit: number
  }
  traceId: string
  rowCount: number
  latencyMs: number
}

export interface Nl2sqlLogView {
  id: number
  userId: number
  role: string
  sessionId: string | null
  question: string
  rowCount: number
  latencyMs: number
  status: string
  rejectReason: string | null
  traceId: string
  createdAt: string
}

export const adminNl2sqlApi = {
  vocabulary(): Promise<Nl2sqlVocabulary> {
    return http.get<Nl2sqlVocabulary>('/admin/nl2sql/vocabulary')
  },

  query(question: string, sessionId: string | null): Promise<Nl2sqlQueryResult> {
    return http.post<Nl2sqlQueryResult>('/admin/nl2sql/query', { question, sessionId })
  },

  logs(page: number, size: number, status: string | null): Promise<PageResponse<Nl2sqlLogView>> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (status) {
      params.set('status', status)
    }
    return http.get<PageResponse<Nl2sqlLogView>>(`/admin/nl2sql/logs?${params.toString()}`)
  },
}
