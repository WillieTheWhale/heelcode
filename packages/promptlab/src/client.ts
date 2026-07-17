import { normalizeCatalog } from "./catalog"
import { buildPromptLabPayload } from "./openai"
import { updatedCookieHeader } from "./cookie"
import { redactError, redactHeaders, redactJSON } from "./redact"
import type {
  OpenAIChatCompletionRequest,
  PromptLabCatalog,
  PromptLabChatResponse,
  PromptLabConfig,
  PromptLabSession,
  ModelSelection,
} from "./types"
import { PromptLabError } from "./types"
import { configWithStoredSession } from "./auth-store"

type RefreshResult = {
  token?: string
  accessToken?: string
  access_token?: string
}
type PromptLabRequestInit = RequestInit & { auth?: boolean }

let promptLabQueue = Promise.resolve()
let nextPromptLabRequestAt = 0

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
    const models = await this.getModels()
    const endpoints = await this.getEndpoints()
    return normalizeCatalog(models, endpoints)
  }

  // Legacy OpenAI compatibility path. PromptLab's provider chat route can expose structured
  // reasoning, but this adapter intentionally reduces it to OpenAI Chat text/tool-call output.
  // Native Heelcode integration must consume the PromptLab event stream directly instead of
  // extending the promptPrefix XML bridge implemented here.
  async completions(request: OpenAIChatCompletionRequest, selection: ModelSelection): Promise<PromptLabChatResponse> {
    const payload = buildPromptLabPayload(request, selection)
    return this.chat(selection.endpoint, payload)
  }

  async chat(endpoint: string, payload: Record<string, unknown>, signal?: AbortSignal): Promise<PromptLabChatResponse> {
    const response = await this.request(`/api/agents/chat/${encodeURIComponent(endpoint)}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
      signal,
    })

    const contentType = response.headers.get("content-type") ?? ""
    if (contentType.includes("text/event-stream")) {
      return { kind: "stream", response }
    }

    const responseText = await response.text().catch(() => "")
    if (looksLikePromptLabSSE(responseText)) return { kind: "stream", response: textStreamResponse(responseText) }

    const value = parseJSON(responseText)
    if (isRecord(value) && typeof value.streamId === "string") {
      const stream = await this.request(`/api/agents/chat/stream/${encodeURIComponent(value.streamId)}`, {
        method: "GET",
        headers: { accept: "text/event-stream" },
        signal,
      })
      const headers = new Headers(stream.headers)
      headers.set("x-promptlab-stream-id", value.streamId)
      return { kind: "stream", response: new Response(stream.body, { status: stream.status, headers }) }
    }

    // If the upstream returned a well-formed OpenAI JSON response, forward it directly.
    if (isOpenAIResponse(value))
      return { kind: "openai", response: new Response(responseText, { headers: response.headers }) }

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
        auth: false,
      },
      { retry: false },
    )
    const value = (await response.json().catch(() => undefined)) as RefreshResult | undefined
    const token = value?.token ?? value?.accessToken ?? value?.access_token
    if (!token) return false
    this.token = token
    this.cookie = updatedCookieHeader(this.cookie, response.headers) ?? this.cookie
    await this.persistSession({ bearerToken: token, cookie: this.cookie })
    return true
  }

  private async json(path: string, init: PromptLabRequestInit = {}): Promise<unknown> {
    const response = await this.request(path, init)
    return response.json()
  }

  private async request(
    path: string,
    init: PromptLabRequestInit = {},
    options: { retry?: boolean } = {},
  ): Promise<Response> {
    const retry = options.retry ?? true
    const { auth = true, ...requestInit } = init
    let refreshed = false
    let recovered = false
    let rateRetries = 0

    while (true) {
      const response = await this.fetchWithThrottle(path, {
        ...requestInit,
        headers: this.headers(requestInit.headers, auth),
      })

      if (response.status === 401 && retry && !refreshed) {
        if (await this.refresh().catch(() => false)) {
          refreshed = true
          continue
        }
        const session = recovered ? undefined : await this.config.recoverSession?.().catch(() => undefined)
        recovered = true
        if (session?.bearerToken) {
          this.token = session.bearerToken
          this.cookie = session.cookie ?? this.cookie
          await this.persistSession({ bearerToken: this.token, cookie: this.cookie })
          refreshed = true
          continue
        }
      }

      if (response.status === 429 && retry && rateRetries < retryAttempts()) {
        await delay(retryDelay(response, rateRetries))
        rateRetries++
        continue
      }

      if (!response.ok) {
        const details = redactJSON(
          await response
            .clone()
            .json()
            .catch(() => undefined),
        )
        const message = `PromptLab request failed: ${response.status} ${response.statusText}`
        throw new PromptLabError(message, response.status, details)
      }

      return response
    }
  }

  private fetchWithThrottle(path: string, init: RequestInit) {
    const task = () => this.fetchImpl(new URL(path, this.config.baseURL), init)
    if (!shouldThrottle(path, init.method)) return task()
    return enqueuePromptLabRequest(task)
  }

  private headers(input?: RequestInit["headers"], auth = true): Headers {
    const headers = new Headers(input)
    headers.set("origin", new URL(this.config.baseURL).origin)
    headers.set("referer", new URL("/c/new", this.config.baseURL).toString())
    headers.set(
      "user-agent",
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
    )
    headers.set("sec-fetch-site", "same-origin")
    headers.set("sec-fetch-mode", "cors")
    headers.set("sec-fetch-dest", "empty")
    if (auth && this.token) headers.set("authorization", `Bearer ${this.token}`)
    if (this.cookie) headers.set("cookie", this.cookie)
    return headers
  }

  private async persistSession(session: PromptLabSession) {
    await this.config.persistSession?.(session).catch(() => {})
  }
}

function shouldThrottle(path: string, method?: string) {
  if ((method ?? "GET").toUpperCase() !== "POST") return false
  return path.includes("/chat/completions") || path.startsWith("/api/agents/chat/")
}

function enqueuePromptLabRequest<T>(task: () => Promise<T>): Promise<T> {
  const run = promptLabQueue
    .catch(() => {})
    .then(async () => {
      const wait = Math.max(0, nextPromptLabRequestAt - Date.now())
      if (wait) await delay(wait)
      nextPromptLabRequestAt = Date.now() + chatIntervalMs()
      return task()
    })
  promptLabQueue = run.then(
    () => undefined,
    () => undefined,
  )
  return run
}

function retryDelay(response: Response, attempt: number) {
  const retryAfter = response.headers.get("retry-after")
  const parsed = retryAfter ? retryAfterMs(retryAfter) : undefined
  if (parsed !== undefined) return parsed
  const base = envNumber("HEELCODE_PROMPTLAB_RETRY_BASE_MS", 2000)
  const max = envNumber("HEELCODE_PROMPTLAB_RETRY_MAX_MS", 60000)
  return Math.min(max, base * 2 ** attempt)
}

function retryAfterMs(value: string) {
  const seconds = Number(value)
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000)
  const date = Date.parse(value)
  if (Number.isFinite(date)) return Math.max(0, date - Date.now())
  return undefined
}

function retryAttempts() {
  return envNumber("HEELCODE_PROMPTLAB_RETRY_ATTEMPTS", 5)
}

function chatIntervalMs() {
  return envNumber("HEELCODE_PROMPTLAB_CHAT_INTERVAL_MS", 1500)
}

function envNumber(key: string, fallback: number) {
  const raw = process.env[key]
  if (raw === undefined) return fallback
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function looksLikePromptLabSSE(text: string) {
  return /^\s*(event|data):/m.test(text)
}

function textStreamResponse(text: string) {
  return new Response(text, {
    headers: {
      "content-type": "text/event-stream",
    },
  })
}

function parseJSON(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

export function configFromEnv(env: Record<string, string | undefined> = process.env): PromptLabConfig {
  return {
    baseURL: env.PROMPTLAB_BASE_URL ?? "https://promptlab.lib.unc.edu",
    bearerToken: env.PROMPTLAB_BEARER_TOKEN,
    cookie: env.PROMPTLAB_COOKIE,
  }
}

export async function configFromEnvOrStore(
  env: Record<string, string | undefined> = process.env,
): Promise<PromptLabConfig> {
  return configWithStoredSession(env)
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

function isOpenAIResponse(value: unknown): boolean {
  if (!isRecord(value)) return false
  return (
    Array.isArray(value.choices) &&
    (value.object === "chat.completion" || value.object === "chat.completion.chunk" || typeof value.id === "string")
  )
}
