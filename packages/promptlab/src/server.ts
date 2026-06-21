import { catalogMetrics, decodeOpenAIModelID, toOpenAIModels } from "./catalog"
import { PromptLabClient, configFromEnv, safeLogError } from "./client"
import {
  lastUserText,
  openAINonStreamingResponse,
  openAIToolCallStream,
  preflightToolCallFromRequest,
  promptLabJSONToText,
  promptLabStreamToText,
  selectionFromRequest,
  toolInstructionFromRequest,
  transformPromptLabSSEToOpenAI,
} from "./openai"
import { redactJSON } from "./redact"
import type { OpenAIChatCompletionRequest, PromptLabConfig } from "./types"

type ServeOptions = {
  port?: number
  hostname?: string
  config?: PromptLabConfig | (() => PromptLabConfig | Promise<PromptLabConfig>)
}

type CatalogResponse = {
  body: ReturnType<typeof toOpenAIModels>
  headers: Record<string, string>
}

export function createHandler(
  config: PromptLabConfig | (() => PromptLabConfig | Promise<PromptLabConfig>) = configFromEnv(),
): (request: Request) => Promise<Response> {
  let catalogCache:
    | (CatalogResponse & {
        expiresAt: number
      })
    | undefined
  let catalogInFlight: Promise<CatalogResponse> | undefined

  return async function handle(request: Request): Promise<Response> {
    const client = new PromptLabClient(await resolveConfig(config))
    const url = new URL(request.url)
    try {
      if (request.method === "OPTIONS") return empty(204)
      if (request.method === "GET" && url.pathname === "/health")
        return json({ ok: true, service: "heelcode-promptlabd" })
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

      if (request.method === "POST" && url.pathname === "/v1/chat/completions") {
        const body = (await request.json()) as OpenAIChatCompletionRequest
        debugChatRequest(body)
        const selection = selectionFromRequest(body)
        const preflightToolCall = preflightToolCallFromRequest(body)
        if (preflightToolCall && body.stream) {
          return new Response(openAIToolCallStream(body.model, preflightToolCall), {
            headers: eventStreamHeaders(),
          })
        }
        const response = await client.startChat(body, selection)

        if (response.kind === "stream") {
          if (!response.response.body) throw new Error("PromptLab returned an empty stream body")
          if (body.stream) {
            const stream = transformPromptLabSSEToOpenAI(response.response.body, body.model, {
              tools: toolInstructionFromRequest(body) ? body.tools : undefined,
            })
            return new Response(stream, {
              headers: eventStreamHeaders(),
            })
          }
          const content = await promptLabStreamToText(response.response.body)
          return json(openAINonStreamingResponse({ model: body.model, content }))
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
      return json({ error: { message: safeLogError(error) } }, { status: 500 })
    }
  }
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
