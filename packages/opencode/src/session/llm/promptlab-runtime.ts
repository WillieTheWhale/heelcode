import { LLMEvent, type ToolDefinition } from "@opencode-ai/llm"
import { isRecord } from "@/util/record"
import type { ModelMessage } from "ai"
import * as Stream from "effect/Stream"

const defaultBaseURL = "http://127.0.0.1:43117/v1"

type Input = {
  readonly sessionID: string
  readonly inferenceScopeID: string
  readonly transient?: boolean
  readonly model: string
  readonly baseURL?: string
  readonly apiKey: string
  readonly messages: readonly ModelMessage[]
  readonly tools: readonly ToolDefinition[]
  readonly toolChoice?: "auto" | "required" | "none"
  readonly temperature?: number
  readonly topP?: number
  readonly maxOutputTokens?: number
  readonly abort: AbortSignal
  readonly fetch: typeof globalThis.fetch
  readonly restart?: () => Promise<void>
}

export function stream(input: Input) {
  return Stream.fromAsyncIterable(events(input), (error) => error)
}

async function* events(input: Input): AsyncGenerator<LLMEvent> {
  const baseURL = input.baseURL ?? defaultBaseURL
  const url = new URL("native/inference", baseURL.endsWith("/") ? baseURL : `${baseURL}/`)
  const request = {
    method: "POST",
    headers: {
      authorization: `Bearer ${input.apiKey}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      sessionID: input.sessionID,
      inferenceScopeID: input.inferenceScopeID,
      transient: input.transient,
      model: input.model,
      messages: input.messages,
      tools: input.tools.map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema,
      })),
      toolChoice: input.toolChoice,
      temperature: input.temperature,
      topP: input.topP,
      maxOutputTokens: input.maxOutputTokens,
    }),
    signal: input.abort,
  }
  const response = await input.fetch(url, request).catch(async (error) => {
    if (input.abort.aborted || baseURL !== defaultBaseURL) throw error
    if (input.restart) await input.restart()
    else {
      const { ensurePromptLabReady } = await import("@/promptlab/daemon")
      await ensurePromptLabReady([])
    }
    return input.fetch(url, request)
  })
  if (!response.ok) throw new Error(await responseError(response))
  if (!response.body) throw new Error("PromptLab native inference returned an empty stream")
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  try {
    for (;;) {
      const chunk = await reader.read()
      if (chunk.done) break
      buffer += decoder.decode(chunk.value, { stream: true })
      for (const event of drain(false)) yield event
    }
    buffer += decoder.decode()
    for (const event of drain(true)) yield event
  } finally {
    await reader.cancel().catch(() => {})
  }

  function drain(flush: boolean) {
    const result: LLMEvent[] = []
    for (;;) {
      const split = buffer.search(/\r?\n\r?\n/)
      if (split === -1) {
        if (!flush || !buffer.trim()) return result
        const block = buffer
        buffer = ""
        const event = wireEvent(block)
        if (event) result.push(event)
        return result
      }
      const block = buffer.slice(0, split)
      buffer = buffer.slice(buffer[split] === "\r" ? split + 4 : split + 2)
      const event = wireEvent(block)
      if (event) result.push(event)
    }
  }
}

async function responseError(response: Response) {
  const body: unknown = await response.clone().json().catch(() => undefined)
  const detail =
    isRecord(body) && isRecord(body.error) && typeof body.error.message === "string" ? body.error.message : undefined
  if (response.status === 401)
    return "PromptLab authentication expired. Sign in to PromptLab in Chrome, then retry HeelCode."
  return [`PromptLab native inference failed: ${response.status} ${response.statusText}`, detail]
    .filter((value): value is string => Boolean(value))
    .join(": ")
}

function wireEvent(block: string): LLMEvent | undefined {
  const data = block
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n")
    .trim()
  if (!data || data === "[DONE]") return undefined
  const value: unknown = JSON.parse(data)
  if (!isRecord(value) || typeof value.type !== "string") throw new Error("Invalid PromptLab native event")
  const metadata = providerMetadata(value.providerMetadata)
  if (value.type === "step-start" && typeof value.index === "number") return LLMEvent.stepStart({ index: value.index })
  if (value.type === "reasoning-start" && typeof value.id === "string")
    return LLMEvent.reasoningStart({ id: value.id, providerMetadata: metadata })
  if (value.type === "reasoning-delta" && typeof value.id === "string" && typeof value.text === "string")
    return LLMEvent.reasoningDelta({ id: value.id, text: value.text, providerMetadata: metadata })
  if (value.type === "reasoning-end" && typeof value.id === "string")
    return LLMEvent.reasoningEnd({ id: value.id, providerMetadata: metadata })
  if (value.type === "text-start" && typeof value.id === "string")
    return LLMEvent.textStart({ id: value.id, providerMetadata: metadata })
  if (value.type === "text-delta" && typeof value.id === "string" && typeof value.text === "string")
    return LLMEvent.textDelta({ id: value.id, text: value.text, providerMetadata: metadata })
  if (value.type === "text-end" && typeof value.id === "string")
    return LLMEvent.textEnd({ id: value.id, providerMetadata: metadata })
  if (value.type === "tool-input-start" && typeof value.id === "string" && typeof value.name === "string")
    return LLMEvent.toolInputStart({ id: value.id, name: value.name, providerMetadata: metadata })
  if (
    value.type === "tool-input-delta" &&
    typeof value.id === "string" &&
    typeof value.name === "string" &&
    typeof value.text === "string"
  )
    return LLMEvent.toolInputDelta({ id: value.id, name: value.name, text: value.text })
  if (value.type === "tool-input-end" && typeof value.id === "string" && typeof value.name === "string")
    return LLMEvent.toolInputEnd({ id: value.id, name: value.name, providerMetadata: metadata })
  if (value.type === "tool-call" && typeof value.id === "string" && typeof value.name === "string")
    return LLMEvent.toolCall({ id: value.id, name: value.name, input: value.input, providerMetadata: metadata })
  if (value.type === "step-finish" && typeof value.index === "number" && finishReason(value.reason))
    return LLMEvent.stepFinish({
      index: value.index,
      reason: value.reason,
      usage: usage(value.usage),
      providerMetadata: metadata,
    })
  if (value.type === "finish" && finishReason(value.reason))
    return LLMEvent.finish({ reason: value.reason, usage: usage(value.usage), providerMetadata: metadata })
  if (value.type === "provider-error" && typeof value.message === "string")
    return LLMEvent.providerError({ message: value.message, providerMetadata: metadata })
  throw new Error(`Unsupported PromptLab native event: ${value.type}`)
}

function providerMetadata(value: unknown) {
  if (!isRecord(value)) return undefined
  const result = Object.fromEntries(
    Object.entries(value).filter((entry): entry is [string, Record<string, unknown>] => isRecord(entry[1])),
  )
  return Object.keys(result).length ? result : undefined
}

function usage(value: unknown) {
  if (!isRecord(value)) return undefined
  return {
    inputTokens: number(value.inputTokens),
    outputTokens: number(value.outputTokens),
    nonCachedInputTokens: number(value.nonCachedInputTokens),
    cacheReadInputTokens: number(value.cacheReadInputTokens),
    cacheWriteInputTokens: number(value.cacheWriteInputTokens),
    totalTokens: number(value.totalTokens),
    providerMetadata: providerMetadata(value.providerMetadata),
  }
}

function finishReason(value: unknown): value is "stop" | "tool-calls" {
  return value === "stop" || value === "tool-calls"
}

function number(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined
}

export * as PromptLabRuntime from "./promptlab-runtime"
