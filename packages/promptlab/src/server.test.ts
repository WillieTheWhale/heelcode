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
})

function fakePromptLabFetch(): typeof fetch {
  const fn = async (input: Request | URL | string, init?: RequestInit) => {
    const url = new URL(String(input))
    if (url.pathname === "/api/models") {
      return json({ openAI: [{ id: "gpt-4.1", name: "GPT-4.1" }] })
    }
    if (url.pathname === "/api/endpoints") {
      return json([{ id: "openAI", name: "OpenAI" }])
    }
    if (url.pathname === "/api/agents/chat/openAI" && init?.method === "POST") {
      return json({ streamId: "stream-1" })
    }
    if (url.pathname === "/api/agents/chat/stream/stream-1") {
      return new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            const encoder = new TextEncoder()
            controller.enqueue(encoder.encode('data: {"message":"Hello"}\n\n'))
            controller.enqueue(encoder.encode('data: {"message":" world"}\n\n'))
            controller.enqueue(encoder.encode('data: {"final":true}\n\n'))
            controller.close()
          },
        }),
        { headers: { "content-type": "text/event-stream" } },
      )
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
