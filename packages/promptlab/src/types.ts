export type JsonObject = Record<string, unknown>

export type PromptLabConfig = {
  baseURL: string
  bearerToken?: string
  cookie?: string
  fetch?: typeof fetch
}

export type PromptLabEndpoint = {
  id: string
  name: string
  raw?: unknown
}

export type PromptLabModel = {
  id: string
  name: string
  endpoint: string
  family?: string
  raw?: unknown
}

export type PromptLabCatalog = {
  endpoints: PromptLabEndpoint[]
  models: PromptLabModel[]
}

export type CatalogMetrics = {
  endpointCount: number
  modelCount: number
  mappedModelCount: number
  duplicateModelIDs: string[]
}

export type OpenAIChatMessage = {
  role: "system" | "user" | "assistant" | "tool" | "developer"
  content:
    | string
    | Array<
        | { type: "text"; text: string }
        | { type: "image_url"; image_url: { url: string } }
        | { type: string; [key: string]: unknown }
      >
    | null
  name?: string
  tool_call_id?: string
}

export type OpenAIChatCompletionRequest = {
  model: string
  messages: OpenAIChatMessage[]
  stream?: boolean
  temperature?: number
  max_tokens?: number
  max_completion_tokens?: number
  top_p?: number
  stop?: string | string[]
  tools?: unknown[]
  tool_choice?: unknown
  metadata?: JsonObject
  user?: string
  [key: string]: unknown
}

export type ModelSelection = {
  openAIModelID: string
  endpoint: string
  model: string
}

export type PromptLabChatStart = {
  request: OpenAIChatCompletionRequest
  selection: ModelSelection
  conversationID?: string
}

export type PromptLabChatResponse =
  | {
      kind: "stream"
      response: Response
    }
  | {
      kind: "openai"
      response: Response
    }
  | {
      kind: "json"
      value: unknown
    }

export class PromptLabError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly details?: unknown,
  ) {
    super(message)
    this.name = "PromptLabError"
  }
}
