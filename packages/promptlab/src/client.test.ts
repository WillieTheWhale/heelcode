import { afterEach, describe, expect, test } from "bun:test"
import { PromptLabClient, safeLogError } from "./client"
import { PromptLabError } from "./types"

const originalEnv = {
  retryAttempts: process.env.HEELCODE_PROMPTLAB_RETRY_ATTEMPTS,
  retryBaseMs: process.env.HEELCODE_PROMPTLAB_RETRY_BASE_MS,
  retryMaxMs: process.env.HEELCODE_PROMPTLAB_RETRY_MAX_MS,
  chatIntervalMs: process.env.HEELCODE_PROMPTLAB_CHAT_INTERVAL_MS,
}

afterEach(() => {
  restoreEnv("HEELCODE_PROMPTLAB_RETRY_ATTEMPTS", originalEnv.retryAttempts)
  restoreEnv("HEELCODE_PROMPTLAB_RETRY_BASE_MS", originalEnv.retryBaseMs)
  restoreEnv("HEELCODE_PROMPTLAB_RETRY_MAX_MS", originalEnv.retryMaxMs)
  restoreEnv("HEELCODE_PROMPTLAB_CHAT_INTERVAL_MS", originalEnv.chatIntervalMs)
})

describe("PromptLab client", () => {
  test("sends same-origin browser headers and stored session material", async () => {
    let seen: Headers | undefined
    const client = new PromptLabClient({
      baseURL: "https://promptlab.example",
      bearerToken: "initial-token",
      cookie: "promptlab.sid=session-cookie",
      fetch: (async (_input: Request | URL | string, init?: RequestInit) => {
        seen = new Headers(init?.headers)
        return json({ ok: true })
      }) as typeof fetch,
    })

    await client.getModels()

    expect(seen?.get("origin")).toBe("https://promptlab.example")
    expect(seen?.get("referer")).toBe("https://promptlab.example/c/new")
    expect(seen?.get("sec-fetch-site")).toBe("same-origin")
    expect(seen?.get("sec-fetch-mode")).toBe("cors")
    expect(seen?.get("authorization")).toBe("Bearer initial-token")
    expect(seen?.get("cookie")).toBe("promptlab.sid=session-cookie")
  })

  test("refreshes expired auth once and retries with replacement token", async () => {
    const calls: Array<{ path: string; authorization: string | null }> = []
    const client = new PromptLabClient({
      baseURL: "https://promptlab.example",
      bearerToken: "expired-token",
      fetch: (async (input: Request | URL | string, init?: RequestInit) => {
        const url = new URL(String(input))
        const authorization = new Headers(init?.headers).get("authorization")
        calls.push({ path: url.pathname, authorization })

        if (url.pathname === "/api/models" && authorization === "Bearer expired-token") {
          return json({ error: "expired" }, 401)
        }
        if (url.pathname === "/api/auth/refresh") {
          return json({ token: "fresh-token" })
        }
        if (url.pathname === "/api/models" && authorization === "Bearer fresh-token") {
          return json({ openAI: ["gpt-4.1"] })
        }
        return json({ error: "unexpected request" }, 500)
      }) as typeof fetch,
    })

    await expect(client.getModels()).resolves.toEqual({ openAI: ["gpt-4.1"] })
    expect(calls).toEqual([
      { path: "/api/models", authorization: "Bearer expired-token" },
      { path: "/api/auth/refresh", authorization: null },
      { path: "/api/models", authorization: "Bearer fresh-token" },
    ])
  })

  test("retries PromptLab 429 responses before surfacing rate-limit failures", async () => {
    process.env.HEELCODE_PROMPTLAB_RETRY_ATTEMPTS = "2"
    process.env.HEELCODE_PROMPTLAB_RETRY_BASE_MS = "0"
    process.env.HEELCODE_PROMPTLAB_RETRY_MAX_MS = "0"

    let calls = 0
    const client = new PromptLabClient({
      baseURL: "https://promptlab.example",
      fetch: (async () => {
        calls++
        if (calls === 1) return json({ error: "slow down" }, 429, { "retry-after": "0" })
        return json({ openAI: ["gpt-4.1"] })
      }) as unknown as typeof fetch,
    })

    await expect(client.getModels()).resolves.toEqual({ openAI: ["gpt-4.1"] })
    expect(calls).toBe(2)
  })

  test("redacts PromptLab errors before logging", () => {
    const text = safeLogError(
      new PromptLabError("Authorization: Bearer abc.def.ghi; promptlab.sid=secret", 500, {
        authorization: "Bearer abc",
        cookie: "promptlab.sid=secret",
        prompt: "private prompt",
      }),
    )

    expect(text).toContain("[REDACTED]")
    expect(text).not.toContain("abc.def.ghi")
    expect(text).not.toContain("secret")
    expect(text).not.toContain("private prompt")
  })
})

function restoreEnv(key: string, value: string | undefined) {
  if (value === undefined) delete process.env[key]
  else process.env[key] = value
}

function json(value: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json",
      ...headers,
    },
  })
}
