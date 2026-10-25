import { http } from './http'
import type { AssistantReply } from './types'

export const assistantApi = {
  chat(message: string, sessionId: string | null): Promise<AssistantReply> {
    return http.post<AssistantReply>('/assistant/chat', { message, sessionId })
  },
}
