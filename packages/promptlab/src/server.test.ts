import { describe, expect, test } from "bun:test"
import { createHandler } from "./server"

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

  test("translates direct PromptLab event streams into OpenAI-compatible SSE", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ chatMode: "direct-stream" }),
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
    expect(text).toContain('"content":"Direct"')
    expect(text).toContain('"content":" stream"')
    expect(text).toContain("data: [DONE]")
  })

  test("surfaces untyped PromptLab SSE errors instead of empty responses", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ chatMode: "untyped-error-stream" }),
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

  test("translates PromptLab streams into OpenAI-compatible SSE", async () => {
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

  test("buffers PromptLab streams for non-streaming OpenAI-compatible responses", async () => {
    const handler = createHandler({ baseURL: "https://promptlab.example", fetch: fakePromptLabFetch() })
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
    expect(response.headers.get("content-type")).toBe("application/json")
    expect(body.choices[0].message.content).toBe("Hello world")
  })

  test("wraps PromptLab JSON responses for streaming and non-streaming OpenAI clients", async () => {
    const handler = createHandler({
      baseURL: "https://promptlab.example",
      fetch: fakePromptLabFetch({ chatMode: "json" }),
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
  chatMode?: "stream-id" | "direct-stream" | "untyped-error-stream" | "json"
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
    if (url.pathname === "/api/agents/chat/openAI" && init?.method === "POST") {
      if (options.chatMode === "direct-stream") {
        return sse(['data: {"message":"Direct"}\n\n', 'data: {"message":" stream"}\n\n', 'data: {"final":true}\n\n'])
      }
      if (options.chatMode === "untyped-error-stream") {
        return new Response('event: error\ndata: {"error":true,"final":true,"text":"{\\"type\\":\\"ban\\"}"}\n\n')
      }
      if (options.chatMode === "json") {
        return json({ message: { content: "JSON hello" } })
      }
      return json({ streamId: "stream-1" })
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
