import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { createHandler } from "./server"

const originalEnv = {
  catalogTTL: process.env.HEELCODE_PROMPTLAB_CATALOG_TTL_MS,
  chatIntervalMs: process.env.HEELCODE_PROMPTLAB_CHAT_INTERVAL_MS,
}

beforeEach(() => {
  process.env.HEELCODE_PROMPTLAB_CHAT_INTERVAL_MS = "0"
})

afterEach(() => {
  restoreEnv("HEELCODE_PROMPTLAB_CATALOG_TTL_MS", originalEnv.catalogTTL)
  restoreEnv("HEELCODE_PROMPTLAB_CHAT_INTERVAL_MS", originalEnv.chatIntervalMs)
})

describe("heelcode-promptlabd handler", () => {
  test("exposes PromptLab models through OpenAI-compatible /v1/models", async () => {
    const handler = createHandler({ baseURL: "https://promptlab.example", fetch: fakePromptLabFetch() })
    const response = await handler(new Request("http://127.0.0.1/v1/models"))
    const body = (await response.json()) as { data: unknown[] }

    expect(response.status).toBe(200)
    expect(body.data).toEqual([
      {
        id: "promptlab/openAI/gpt-4.1",
        object: "model",
        created: 0,
        owned_by: "promptlab",
        name: "GPT-4.1",
        endpoint: "openAI",
      },
    ])
    expect(response.headers.get("x-heelcode-model-count")).toBe("1")
  })

  test("caches PromptLab model catalog responses", async () => {
    process.env.HEELCODE_PROMPTLAB_CATALOG_TTL_MS = "60000"
    let models = 0
    let endpoints = 0
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: (async (input: Request | URL | string) => {
        const url = new URL(String(input))
        if (url.pathname === "/api/models") {
          models++
          await delay(5)
          return json({ openAI: [{ id: "gpt-4.1", name: "GPT-4.1" }] })
        }
        if (url.pathname === "/api/endpoints") {
          endpoints++
          await delay(5)
          return json([{ id: "openAI", name: "OpenAI" }])
        }
        return json({ message: "not found" }, 404)
      }) as typeof fetch,
    })

    await Promise.all([
      handler(new Request("http://127.0.0.1/v1/models")),
      handler(new Request("http://127.0.0.1/v1/models")),
    ])
    await handler(new Request("http://127.0.0.1/v1/models"))

    expect(models).toBe(1)
    expect(endpoints).toBe(1)
  })

  test("forwards bare OpenAI-compatible streaming responses directly", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ completionsMode: "openai-stream" }),
    })
    const response = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          stream: true,
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const text = await response.text()

    expect(response.status).toBe(200)
    expect(response.headers.get("content-type")).toBe("text/event-stream")
    expect(text).toContain('"content":"Hello"')
    expect(text).toContain('"content":" world"')
    expect(text).toContain("data: [DONE]")
  })

  test("forwards bare OpenAI-compatible non-streaming responses directly", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ completionsMode: "openai-json" }),
    })
    const response = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const body = (await response.json()) as { choices: Array<{ message: { content: string } }> }

    expect(response.status).toBe(200)
    expect(body.choices[0].message.content).toBe("Hello world")
  })

  test("forwards bare OpenAI-compatible tool call responses", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ completionsMode: "openai-tool-call" }),
    })
    const response = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          stream: true,
          messages: [{ role: "user", content: "list files" }],
          tools: [{ type: "function", function: { name: "glob", parameters: {} } }],
          tool_choice: "auto",
        }),
      }),
    )
    const text = await response.text()

    expect(response.status).toBe(200)
    expect(text).toContain('"tool_calls"')
    expect(text).toContain('"name":"glob"')
    expect(text).toContain('"finish_reason":"tool_calls"')
  })

  test("sends a clean payload to PromptLab: promptPrefix for system, no synthetic tool instructions", async () => {
    const calls: Array<{ path: string; method: string; body?: unknown }> = []
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ calls, completionsMode: "openai-json" }),
    })

    const tools = [{ type: "function", function: { name: "glob", parameters: {} } }]
    await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          messages: [
            { role: "system", content: "Be concise." },
            { role: "user", content: "hello" },
          ],
          tools,
          tool_choice: "auto",
        }),
      }),
    )

    const call = calls.find((c) => c.path === "/api/agents/chat/openAI")
    expect(call).toBeDefined()
    const body = call!.body as Record<string, unknown>
    // System message in promptPrefix; tool instructions appended after it.
    expect(String(body.promptPrefix)).toContain("Be concise.")
    expect(String(body.promptPrefix)).toContain("heelcode_tool_call")
    expect(String(body.promptPrefix)).toContain("glob")
    // System message not doubled into text.
    expect(String(body.text)).not.toContain("Be concise.")
    // Native tools NOT forwarded (injected as text in promptPrefix instead).
    expect(body).not.toHaveProperty("tools")
    expect(body).not.toHaveProperty("tool_choice")
    // No old-style synthetic tool instruction text in message body.
    expect(String(body.text)).not.toContain("Heelcode local opencode tools are available")
    // Standard PromptLab routing fields present.
    expect(body.model).toBe("gpt-4.1")
    expect(body.endpoint).toBe("openAI")
    expect(typeof body.conversationId).toBe("string")
  })

  test("translates PromptLab SSE event-stream responses into OpenAI-compatible SSE as fallback", async () => {
    const handler = createHandler({ baseURL: "https://promptlab.example", fetch: fakePromptLabFetch() })
    const response = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          stream: true,
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const text = await response.text()

    expect(response.status).toBe(200)
    expect(response.headers.get("content-type")).toBe("text/event-stream")
    expect(text).toContain('"content":"Hello"')
    expect(text).toContain('"content":" world"')
    expect(text).toContain("data: [DONE]")
  })

  test("surfaces untyped PromptLab SSE errors instead of empty responses", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ completionsMode: "untyped-error-stream" }),
    })
    const response = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const text = await response.text()

    expect(response.status).toBe(500)
    expect(text).toContain("ban")
  })

  test("keeps streaming PromptLab SSE errors OpenAI-compatible", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ completionsMode: "untyped-error-stream" }),
    })
    const response = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          stream: true,
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const text = await response.text()

    expect(response.status).toBe(200)
    expect(text).toContain("PromptLab stream error")
    expect(text).toContain("data: [DONE]")
  })

  test("wraps PromptLab JSON responses for streaming and non-streaming OpenAI clients", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ completionsMode: "promptlab-json" }),
    })

    const nonStreaming = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const nonStreamingBody = (await nonStreaming.json()) as { choices: Array<{ message: { content: string } }> }
    expect(nonStreaming.status).toBe(200)
    expect(nonStreamingBody.choices[0].message.content).toBe("JSON hello")

    const streaming = await handler(
      new Request("http://127.0.0.1/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify({
          model: "promptlab/openAI/gpt-4.1",
          stream: true,
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    const streamingText = await streaming.text()
    expect(streaming.status).toBe(200)
    expect(streaming.headers.get("content-type")).toBe("text/event-stream")
    expect(streamingText).toContain('"content":"JSON hello"')
    expect(streamingText).toContain("data: [DONE]")
  })

  test("proxies PromptLab active, status, and abort workflows", async () => {
    const calls: Array<{ path: string; method: string; body?: unknown }> = []
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ calls }),
    })

    const active = await handler(new Request("http://127.0.0.1/promptlab/active"))
    const status = await handler(new Request("http://127.0.0.1/promptlab/status/conversation-1"))
    const abort = await handler(
      new Request("http://127.0.0.1/v1/chat/abort", {
        method: "POST",
        body: JSON.stringify({ conversationID: "conversation-1", streamID: "stream-1" }),
      }),
    )

    await expect(active.json()).resolves.toEqual({ conversations: ["conversation-1"] })
    await expect(status.json()).resolves.toEqual({ id: "conversation-1", status: "running" })
    await expect(abort.json()).resolves.toEqual({ aborted: true })
    expect(calls).toContainEqual({ path: "/api/agents/chat/active", method: "GET" })
    expect(calls).toContainEqual({ path: "/api/agents/chat/status/conversation-1", method: "GET" })
    expect(calls).toContainEqual({
      path: "/api/agents/chat/abort",
      method: "POST",
      body: { conversationID: "conversation-1", streamID: "stream-1" },
    })
  })
})

type FakePromptLabOptions = {
  completionsMode?: "openai-stream" | "openai-json" | "openai-tool-call" | "promptlab-json" | "untyped-error-stream"
  calls?: Array<{ path: string; method: string; body?: unknown }>
}

function fakePromptLabFetch(options: FakePromptLabOptions = {}): typeof fetch {
  const fn = async (input: Request | URL | string, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? "GET"
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    options.calls?.push({ path: url.pathname, method, ...(body === undefined ? {} : { body }) })

    if (url.pathname === "/api/models") {
      return json({ openAI: [{ id: "gpt-4.1", name: "GPT-4.1" }] })
    }
    if (url.pathname === "/api/endpoints") {
      return json([{ id: "openAI", name: "OpenAI" }])
    }

    // PromptLab chatbox endpoint — the only model call path available.
    if (url.pathname === "/api/agents/chat/openAI" && method === "POST") {
      if (options.completionsMode === "openai-stream") {
        // Simulate PromptLab returning a stream-id that resolves to an SSE stream with OpenAI deltas.
        return json({ streamId: "stream-openai" })
      }
      if (options.completionsMode === "openai-json") {
        return json({
          streamId: "stream-openai-json",
        })
      }
      if (options.completionsMode === "openai-tool-call") {
        return json({ streamId: "stream-tool-call" })
      }
      if (options.completionsMode === "untyped-error-stream") {
        return new Response('event: error\ndata: {"error":true,"final":true,"text":"{\\"type\\":\\"ban\\"}"}\n\n')
      }
      if (options.completionsMode === "promptlab-json") {
        return json({ message: { content: "JSON hello" } })
      }
      // Default: stream-id flow.
      return json({ streamId: "stream-1" })
    }

    // Stream fetch paths.
    if (url.pathname === "/api/agents/chat/stream/stream-openai") {
      return sseOpenAI([
        { choices: [{ delta: { content: "Hello" }, finish_reason: null, index: 0 }] },
        { choices: [{ delta: { content: " world" }, finish_reason: null, index: 0 }] },
        { choices: [{ delta: {}, finish_reason: "stop", index: 0 }] },
      ])
    }
    if (url.pathname === "/api/agents/chat/stream/stream-openai-json") {
      return sse([`data: ${JSON.stringify({ final: true, responseMessage: { text: "Hello world" } })}\n\n`])
    }
    if (url.pathname === "/api/agents/chat/stream/stream-tool-call") {
      return sseOpenAI([
        {
          choices: [
            {
              delta: {
                tool_calls: [{ index: 0, id: "call_1", type: "function", function: { name: "glob", arguments: '{"pattern":"*"}' } }],
              },
              finish_reason: null,
              index: 0,
            },
          ],
        },
        { choices: [{ delta: {}, finish_reason: "tool_calls", index: 0 }] },
      ])
    }

    if (url.pathname === "/api/agents/chat/stream/stream-1") {
      return sse(['data: {"message":"Hello"}\n\n', 'data: {"message":" world"}\n\n', 'data: {"final":true}\n\n'])
    }

    if (url.pathname === "/api/agents/chat/status/conversation-1") {
      return json({ id: "conversation-1", status: "running" })
    }
    if (url.pathname === "/api/agents/chat/active") {
      return json({ conversations: ["conversation-1"] })
    }
    if (url.pathname === "/api/agents/chat/abort" && method === "POST") {
      return json({ aborted: true })
    }
    return json({ message: "not found" }, 404)
  }
  return fn as typeof fetch
}

function restoreEnv(key: string, value: string | undefined) {
  if (value === undefined) delete process.env[key]
  else process.env[key] = value
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json",
    },
  })
}

function sse(events: string[]): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        const encoder = new TextEncoder()
        for (const event of events) controller.enqueue(encoder.encode(event))
        controller.close()
      },
    }),
    { headers: { "content-type": "text/event-stream" } },
  )
}

function sseOpenAI(chunks: unknown[]): Response {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`))
        }
        controller.enqueue(encoder.encode("data: [DONE]\n\n"))
        controller.close()
      },
    }),
    { headers: { "content-type": "text/event-stream" } },
  )
}
