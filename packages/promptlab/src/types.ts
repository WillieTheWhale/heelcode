export type JsonObject = Record<string, unknown>

export type PromptLabConfig = {
  baseURL: string
  bearerToken?: string
  cookie?: string
  fetch?: typeof fetch
  recoverSession?: () => Promise<PromptLabSession | undefined>
  persistSession?: (session: PromptLabSession) => Promise<void>
}

export type PromptLabSession = {
  bearerToken?: string
  cookie?: string
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

export type PromptLabContinuation = {
  conversationID: string
  parentMessageID: string
  endpoint: string
  model: string
}

export type PromptLabNativeTool = {
  name: string
  description: string
  inputSchema: JsonObject
}

export type PromptLabNativeMessage = {
  role: "system" | "user" | "assistant" | "tool"
  content: unknown
}

export type PromptLabNativeRequest = {
  sessionID: string
  inferenceScopeID?: string
  transient?: boolean
  model: string
  messages: PromptLabNativeMessage[]
  tools: PromptLabNativeTool[]
  toolChoice?: "auto" | "required" | "none"
  temperature?: number
  topP?: number
  maxOutputTokens?: number
}

export type PromptLabNativeEvent =
  | { type: "step-start"; index: number; providerMetadata: JsonObject }
  | { type: "reasoning-start"; id: string; providerMetadata: JsonObject }
  | { type: "reasoning-delta"; id: string; text: string; providerMetadata: JsonObject }
  | { type: "reasoning-end"; id: string; providerMetadata: JsonObject }
  | { type: "text-start"; id: string; providerMetadata: JsonObject }
  | { type: "text-delta"; id: string; text: string; providerMetadata: JsonObject }
  | { type: "text-end"; id: string; providerMetadata: JsonObject }
  | { type: "tool-input-start"; id: string; name: string; providerMetadata: JsonObject }
  | { type: "tool-input-delta"; id: string; name: string; text: string }
  | { type: "tool-input-end"; id: string; name: string; providerMetadata: JsonObject }
  | { type: "tool-call"; id: string; name: string; input: unknown; providerMetadata: JsonObject }
  | {
      type: "step-finish"
      index: number
      reason: "stop" | "tool-calls"
      usage?: JsonObject
      providerMetadata: JsonObject
    }
  | { type: "finish"; reason: "stop" | "tool-calls"; usage?: JsonObject; providerMetadata: JsonObject }
  | { type: "provider-error"; message: string; providerMetadata: JsonObject }

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
