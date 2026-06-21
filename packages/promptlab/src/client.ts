import { normalizeCatalog } from "./catalog"
import { buildPromptLabPayload } from "./openai"
import { redactError, redactHeaders, redactJSON } from "./redact"
import type {
  OpenAIChatCompletionRequest,
  PromptLabCatalog,
  PromptLabChatResponse,
  PromptLabConfig,
  ModelSelection,
} from "./types"
import { PromptLabError } from "./types"

type RefreshResult = {
  token?: string
  accessToken?: string
  access_token?: string
}

export class PromptLabClient {
  private token: string | undefined
  private cookie: string | undefined
  private fetchImpl: typeof fetch

  constructor(private readonly config: PromptLabConfig) {
    this.token = config.bearerToken
    this.cookie = config.cookie
    this.fetchImpl = config.fetch ?? fetch
  }

  async getConfig(): Promise<unknown> {
    return this.json("/api/config", { auth: false })
  }

  async getEndpoints(): Promise<unknown> {
    return this.json("/api/endpoints")
  }

  async getModels(): Promise<unknown> {
    return this.json("/api/models")
  }

  async getCatalog(): Promise<PromptLabCatalog> {
    const [models, endpoints] = await Promise.all([this.getModels(), this.getEndpoints()])
    return normalizeCatalog(models, endpoints)
  }

  async startChat(request: OpenAIChatCompletionRequest, selection: ModelSelection): Promise<PromptLabChatResponse> {
    const payload = buildPromptLabPayload(request, selection)
    const endpoint = encodeURIComponent(selection.endpoint)
    const response = await this.request(`/api/agents/chat/${endpoint}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    })

    if (response.headers.get("content-type")?.includes("text/event-stream")) {
      return { kind: "stream", response }
    }

    const value = await response.json().catch(() => undefined)
    if (isRecord(value) && typeof value.streamId === "string") {
      const stream = await this.request(`/api/agents/chat/stream/${encodeURIComponent(value.streamId)}`, {
        method: "GET",
        headers: {
          accept: "text/event-stream",
        },
      })
      return { kind: "stream", response: stream }
    }
    return { kind: "json", value }
  }

  async status(conversationID: string): Promise<unknown> {
    return this.json(`/api/agents/chat/status/${encodeURIComponent(conversationID)}`)
  }

  async active(): Promise<unknown> {
    return this.json("/api/agents/chat/active")
  }

  async abort(input: { conversationID?: string; streamID?: string }): Promise<unknown> {
    return this.json("/api/agents/chat/abort", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(input),
    })
  }

  async refresh(): Promise<boolean> {
    const response = await this.request(
      "/api/auth/refresh",
      {
        method: "POST",
        headers: { "content-type": "application/json" },
      },
      { retry: false },
    )
    const value = (await response.json().catch(() => undefined)) as RefreshResult | undefined
    const token = value?.token ?? value?.accessToken ?? value?.access_token
    if (!token) return false
    this.token = token
    return true
  }

  private async json(path: string, init: RequestInit & { auth?: boolean } = {}): Promise<unknown> {
    const response = await this.request(path, init)
    return response.json()
  }

  private async request(path: string, init: RequestInit = {}, options: { retry?: boolean } = {}): Promise<Response> {
    const retry = options.retry ?? true
    const response = await this.fetchImpl(new URL(path, this.config.baseURL), {
      ...init,
      headers: this.headers(init.headers),
    })

    if (response.status === 401 && retry && (await this.refresh().catch(() => false))) {
      return this.request(path, init, { retry: false })
    }

    if (!response.ok) {
      const details = redactJSON(await response.clone().json().catch(() => undefined))
      const message = `PromptLab request failed: ${response.status} ${response.statusText}`
      throw new PromptLabError(message, response.status, details)
    }

    return response
  }

  private headers(input?: RequestInit["headers"]): Headers {
    const headers = new Headers(input)
    if (this.token) headers.set("authorization", `Bearer ${this.token}`)
    if (this.cookie) headers.set("cookie", this.cookie)
    return headers
  }
}

export function configFromEnv(env: Record<string, string | undefined> = process.env): PromptLabConfig {
  return {
    baseURL: env.PROMPTLAB_BASE_URL ?? "https://promptlab.lib.unc.edu",
    bearerToken: env.PROMPTLAB_BEARER_TOKEN,
    cookie: env.PROMPTLAB_COOKIE,
  }
}

export function safeLogError(error: unknown): string {
  if (isPromptLabLikeError(error)) {
    return JSON.stringify({
      name: error.name,
      message: redactError(error.message),
      status: error.status,
      details: redactJSON(error.details),
      headers: error.headers ? redactHeaders(error.headers) : undefined,
    })
  }
  return redactError(error)
}

function isPromptLabLikeError(input: unknown): input is {
  name: string
  message: string
  status?: number
  details?: unknown
  headers?: Record<string, string>
} {
  return isRecord(input) && typeof input.name === "string" && typeof input.message === "string"
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input)
}
