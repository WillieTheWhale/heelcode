import { catalogMetrics, decodeOpenAIModelID, toOpenAIModels } from "./catalog"
import { PromptLabClient, configFromEnv, safeLogError } from "./client"
import {
  lastUserText,
  openAINonStreamingResponse,
  promptLabJSONToText,
  promptLabStreamToOpenAINonStreaming,
  promptLabStreamToText,
  selectionFromRequest,
  transformPromptLabSSEToOpenAI,
} from "./openai"
import { redactJSON } from "./redact"
import { nativeInference } from "./native"
import type {
  OpenAIChatCompletionRequest,
  PromptLabConfig,
  PromptLabContinuation,
  PromptLabNativeRequest,
} from "./types"
import { PromptLabError } from "./types"

type ServeOptions = {
  port?: number
  hostname?: string
  config?: PromptLabConfig | (() => PromptLabConfig | Promise<PromptLabConfig>)
}

type ServerControl = {
  timeout(request: Request, seconds: number): void
  stop?(closeActiveConnections?: boolean): void
}

export const PROMPTLAB_PROTOCOL_VERSION = 2

type CatalogResponse = {
  body: ReturnType<typeof toOpenAIModels>
  headers: Record<string, string>
}

export function createHandler(
  config: PromptLabConfig | (() => PromptLabConfig | Promise<PromptLabConfig>) = configFromEnv(),
): (request: Request, server?: ServerControl) => Promise<Response> {
  let catalogCache:
    | (CatalogResponse & {
        expiresAt: number
      })
    | undefined
  let catalogInFlight: Promise<CatalogResponse> | undefined
  const nativeSessions = new Map<string, PromptLabContinuation>()
  const activeNativeSessions = new Set<string>()

  return async function handle(request: Request, server?: ServerControl): Promise<Response> {
    const client = new PromptLabClient(await resolveConfig(config))
    const url = new URL(request.url)
    try {
      if (request.method === "OPTIONS") return empty(204)
      if (request.method === "GET" && url.pathname === "/health")
        return json({ ok: true, service: "heelcode-promptlabd", protocol: PROMPTLAB_PROTOCOL_VERSION, pid: process.pid })
      if (request.method === "POST" && url.pathname === "/shutdown") {
        setTimeout(() => server?.stop?.(), 0)
        return json({ ok: true })
      }
      if (request.method === "GET" && url.pathname === "/v1/models") {
        if (catalogCache && catalogCache.expiresAt > Date.now())
          return json(catalogCache.body, { headers: catalogCache.headers })
        catalogInFlight ??= loadCatalog(client).finally(() => {
          catalogInFlight = undefined
        })
        const result = await catalogInFlight
        const ttl = catalogTTL()
        if (ttl > 0) catalogCache = { ...result, expiresAt: Date.now() + ttl }
        return json(result.body, { headers: result.headers })
      }

      if (request.method === "POST" && url.pathname === "/v1/native/inference") {
        // Provider reasoning can remain quiet for longer than Bun's 10-second default.
        // A quiet SSE response is still active and must not be reset by the loopback server.
        server?.timeout(request, 0)
        const body = requireNativeRequest(await request.json())
        const scopeID = body.inferenceScopeID ?? body.sessionID
        if (activeNativeSessions.has(scopeID))
          return json(
            { error: { message: "A PromptLab inference turn is already active for this inference scope" } },
            { status: 409 },
          )
        activeNativeSessions.add(scopeID)
        const result = await nativeInference({
          client,
          request: body,
          continuation: nativeSessions.get(scopeID),
          signal: request.signal,
          onContinuation: body.transient ? undefined : (continuation) => nativeSessions.set(scopeID, continuation),
          onSettled: () => activeNativeSessions.delete(scopeID),
        }).catch((error) => {
          activeNativeSessions.delete(scopeID)
          throw error
        })
        const abort = () => {
          activeNativeSessions.delete(scopeID)
          void client.abort({ conversationID: result.conversationID, streamID: result.streamID }).catch(() => {})
        }
        if (request.signal.aborted) abort()
        else request.signal.addEventListener("abort", abort, { once: true })
        return new Response(result.stream, { headers: eventStreamHeaders() })
      }

      if (request.method === "POST" && url.pathname === "/v1/chat/completions") {
        const body = (await request.json()) as OpenAIChatCompletionRequest
        debugChatRequest(body)
        const selection = selectionFromRequest(body)
        const response = await client.completions(body, selection)

        if (response.kind === "stream") {
          if (!response.response.body) throw new Error("PromptLab returned an empty stream body")
          if (body.stream) {
            const stream = transformPromptLabSSEToOpenAI(response.response.body, body.model)
            return new Response(stream, {
              headers: eventStreamHeaders(),
            })
          }
          return json(await promptLabStreamToOpenAINonStreaming(response.response.body, body.model))
        }

        if (response.kind === "openai") {
          // Raw OpenAI-compatible response: proxy it directly.
          return new Response(response.response.body, {
            status: response.response.status,
            headers: forwardHeaders(response.response.headers, body.stream),
          })
        }

        const content = promptLabJSONToText(response.value)
        if (body.stream) {
          return jsonToOpenAIStream(body.model, content)
        }
        return json(openAINonStreamingResponse({ model: body.model, content }))
      }

      if (request.method === "POST" && url.pathname === "/v1/chat/abort") {
        return json(
          await client.abort(
            (await request.json().catch(() => ({}))) as { conversationID?: string; streamID?: string },
          ),
        )
      }

      if (request.method === "GET" && url.pathname.startsWith("/promptlab/status/")) {
        const conversationID = decodeURIComponent(url.pathname.slice("/promptlab/status/".length))
        return json(await client.status(conversationID))
      }

      if (request.method === "GET" && url.pathname === "/promptlab/active") {
        return json(await client.active())
      }

      if (request.method === "GET" && url.pathname === "/promptlab/config") {
        return json(redactJSON(await client.getConfig()))
      }

      return json({ error: { message: "Not found" } }, { status: 404 })
    } catch (error) {
      return json(
        { error: { message: safeLogError(error) } },
        { status: error instanceof PromptLabError && error.status === 401 ? 401 : 500 },
      )
    }
  }
}

function requireNativeRequest(value: unknown): PromptLabNativeRequest {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Native inference body is required")
  const input = value as Record<string, unknown>
  if (typeof input.sessionID !== "string" || !input.sessionID) throw new Error("Native inference sessionID is required")
  if (input.inferenceScopeID !== undefined && (typeof input.inferenceScopeID !== "string" || !input.inferenceScopeID))
    throw new Error("Native inference inferenceScopeID must be a non-empty string")
  if (input.transient !== undefined && typeof input.transient !== "boolean")
    throw new Error("Native inference transient must be a boolean")
  if (typeof input.model !== "string" || !input.model) throw new Error("Native inference model is required")
  if (!Array.isArray(input.messages)) throw new Error("Native inference messages must be an array")
  if (!Array.isArray(input.tools)) throw new Error("Native inference tools must be an array")
  return value as PromptLabNativeRequest
}

function debugChatRequest(body: OpenAIChatCompletionRequest) {
  if (process.env.HEELCODE_PROMPTLAB_DEBUG_REQUESTS !== "1") return
  const lastUser = lastUserText(body.messages ?? [])
  const tools = Array.isArray(body.tools)
    ? body.tools.flatMap((tool) => {
        if (!tool || typeof tool !== "object") return []
        const value = tool as Record<string, unknown>
        const fn = value.function
        if (fn && typeof fn === "object" && "name" in fn && typeof fn.name === "string") return [fn.name]
        if ("name" in value && typeof value.name === "string") return [value.name]
        return []
      })
    : []
  console.error(
    JSON.stringify({
      heelcodePromptLabRequest: true,
      model: body.model,
      stream: body.stream,
      toolChoice: body.tool_choice,
      tools,
      messageRoles: body.messages?.map((message) => message.role),
      lastUserLength: lastUser.length,
    }),
  )
}

async function loadCatalog(client: PromptLabClient): Promise<CatalogResponse> {
  const catalog = await client.getCatalog()
  return {
    body: toOpenAIModels(catalog),
    headers: {
      "x-heelcode-endpoint-count": String(catalogMetrics(catalog).endpointCount),
      "x-heelcode-model-count": String(catalogMetrics(catalog).modelCount),
    },
  }
}

export function serve(options: ServeOptions = {}) {
  const port = options.port ?? Number(process.env.HEELCODE_PROMPTLAB_PORT ?? 43117)
  const hostname = options.hostname ?? process.env.HEELCODE_PROMPTLAB_HOST ?? "127.0.0.1"
  const handler = createHandler(options.config ?? configFromEnv())

  return Bun.serve({
    port,
    hostname,
    fetch: handler,
  })
}

async function resolveConfig(config: PromptLabConfig | (() => PromptLabConfig | Promise<PromptLabConfig>)) {
  return typeof config === "function" ? await config() : config
}

function catalogTTL() {
  const raw = process.env.HEELCODE_PROMPTLAB_CATALOG_TTL_MS
  if (raw === undefined) return 60000
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : 60000
}

function json(value: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers({
    "content-type": "application/json",
    "access-control-allow-origin": "http://127.0.0.1",
    "access-control-allow-headers": "authorization,content-type",
    "access-control-allow-methods": "GET,POST,OPTIONS",
  })
  mergeHeaders(headers, init.headers)
  return new Response(JSON.stringify(value), {
    ...init,
    headers,
  })
}

function mergeHeaders(headers: Headers, input: ResponseInit["headers"]) {
  if (!input) return
  if (input instanceof Headers) {
    input.forEach((value, key) => headers.set(key, value))
    return
  }
  if (Array.isArray(input)) {
    for (const [key, value] of input) headers.set(key, value)
    return
  }
  for (const [key, value] of Object.entries(input)) {
    if (typeof value === "string") headers.set(key, value)
    else if (Array.isArray(value)) headers.set(key, value.join(", "))
  }
}

function forwardHeaders(upstream: Headers, streaming: boolean | undefined): Headers {
  const headers = new Headers({
    "access-control-allow-origin": "http://127.0.0.1",
  })
  const contentType = upstream.get("content-type")
  if (contentType) headers.set("content-type", contentType)
  if (streaming) {
    headers.set("cache-control", "no-cache")
    headers.set("connection", "keep-alive")
  }
  return headers
}

function empty(status: number): Response {
  return new Response(null, {
    status,
    headers: {
      "access-control-allow-origin": "http://127.0.0.1",
      "access-control-allow-headers": "authorization,content-type",
      "access-control-allow-methods": "GET,POST,OPTIONS",
    },
  })
}

function eventStreamHeaders(): Record<string, string> {
  return {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
    "access-control-allow-origin": "http://127.0.0.1",
  }
}

function jsonToOpenAIStream(model: string, content: string): Response {
  const encoder = new TextEncoder()
  const id = `chatcmpl-${crypto.randomUUID()}`
  const chunk = {
    id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: { content }, finish_reason: null }],
  }
  const done = {
    id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
  }
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`))
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(done)}\n\n`))
        controller.enqueue(encoder.encode("data: [DONE]\n\n"))
        controller.close()
      },
    }),
    { headers: eventStreamHeaders() },
  )
}

export function requirePromptLabModelID(model: string) {
  const selection = decodeOpenAIModelID(model)
  if (!selection) throw new Error(`Invalid PromptLab model id: ${model}`)
  return selection
}
