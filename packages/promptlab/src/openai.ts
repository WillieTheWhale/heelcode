import { decodeOpenAIModelID } from "./catalog"
import type { ModelSelection, OpenAIChatCompletionRequest, OpenAIChatMessage } from "./types"

const encoder = new TextEncoder()
const decoder = new TextDecoder()

export function messageContentToText(content: OpenAIChatMessage["content"]): string {
  if (typeof content === "string") return content
  if (!content) return ""
  return content
    .map((part) => {
      if (part.type === "text" && typeof part.text === "string") return part.text
      if (part.type === "image_url") return "[image]"
      return ""
    })
    .filter(Boolean)
    .join("\n")
}

export function messagesToPromptText(messages: OpenAIChatMessage[]): string {
  return messages
    .map((message) => {
      const text = messageContentToText(message.content)
      if (!text) return ""
      if (message.role === "user") return text
      return `${message.role}: ${text}`
    })
    .filter(Boolean)
    .join("\n\n")
}

export function lastUserText(messages: OpenAIChatMessage[]): string {
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index]
    if (message.role === "user") return messageContentToText(message.content)
  }
  return messagesToPromptText(messages)
}

export function buildPromptLabPayload(request: OpenAIChatCompletionRequest, selection: ModelSelection) {
  const conversationID = crypto.randomUUID()
  const messageID = crypto.randomUUID()
  const parentMessageID = "00000000-0000-0000-0000-000000000000"
  const text = lastUserText(request.messages)

  return {
    endpoint: selection.endpoint,
    model: selection.model,
    messages: request.messages,
    text,
    prompt: text,
    conversationId: conversationID,
    parentMessageId: parentMessageID,
    messageId: messageID,
    isTemporary: true,
    isRegenerate: false,
    isContinued: false,
    temperature: request.temperature,
    max_tokens: request.max_tokens ?? request.max_completion_tokens,
    top_p: request.top_p,
    stop: request.stop,
  }
}

export function selectionFromRequest(request: OpenAIChatCompletionRequest): ModelSelection {
  const selection = decodeOpenAIModelID(request.model)
  if (!selection) {
    throw new Error(`Expected PromptLab model id in the form promptlab/<endpoint>/<model>, got ${request.model}`)
  }
  return selection
}

export function openAINonStreamingResponse(params: {
  id?: string
  model: string
  content: string
  finishReason?: string
}) {
  return {
    id: params.id ?? `chatcmpl-${crypto.randomUUID()}`,
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: params.model,
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: params.content,
        },
        finish_reason: params.finishReason ?? "stop",
      },
    ],
  }
}

export function promptLabJSONToText(value: unknown): string {
  if (typeof value === "string") return value
  if (!isRecord(value)) return ""
  const direct = stringValue(value.text) ?? stringValue(value.content) ?? stringValue(value.response) ?? stringValue(value.output)
  if (direct) return direct
  if (isRecord(value.message)) {
    return stringValue(value.message.content) ?? stringValue(value.message.text) ?? ""
  }
  if (Array.isArray(value.choices)) {
    const first = value.choices[0]
    if (isRecord(first) && isRecord(first.message)) return stringValue(first.message.content) ?? ""
  }
  return ""
}

export function openAIChunk(params: { id: string; model: string; content?: string; finishReason?: string }) {
  return {
    id: params.id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model: params.model,
    choices: [
      {
        index: 0,
        delta: params.content === undefined ? {} : { content: params.content },
        finish_reason: params.finishReason ?? null,
      },
    ],
  }
}

export function transformPromptLabSSEToOpenAI(input: ReadableStream<Uint8Array>, model: string): ReadableStream<Uint8Array> {
  const id = `chatcmpl-${crypto.randomUUID()}`
  let buffer = ""
  let closed = false

  return new ReadableStream<Uint8Array>({
    async start(controller) {
      const reader = input.getReader()
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          flushEvents(controller, id, model)
        }
        buffer += decoder.decode()
        flushEvents(controller, id, model, true)
        close(controller, id, model)
      } catch (error) {
        controller.error(error)
      }
    },
  })

  function flushEvents(controller: ReadableStreamDefaultController<Uint8Array>, id: string, model: string, flush = false) {
    for (;;) {
      const split = buffer.search(/\r?\n\r?\n/)
      if (split === -1) {
        if (!flush || !buffer.trim()) return
        const event = buffer
        buffer = ""
        emitEvent(controller, id, model, event)
        return
      }
      const event = buffer.slice(0, split)
      buffer = buffer.slice(buffer[split] === "\r" ? split + 4 : split + 2)
      emitEvent(controller, id, model, event)
    }
  }

  function emitEvent(controller: ReadableStreamDefaultController<Uint8Array>, id: string, model: string, event: string) {
    const data = event
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trimStart())
      .join("\n")
      .trim()
    if (!data || data === "[DONE]") {
      if (data === "[DONE]") close(controller, id, model)
      return
    }

    const delta = promptLabEventToDelta(data)
    if (delta.done) {
      close(controller, id, model)
      return
    }
    if (!delta.content) return
    controller.enqueue(sse(openAIChunk({ id, model, content: delta.content })))
  }

  function close(controller: ReadableStreamDefaultController<Uint8Array>, id: string, model: string) {
    if (closed) return
    closed = true
    controller.enqueue(sse(openAIChunk({ id, model, finishReason: "stop" })))
    controller.enqueue(encoder.encode("data: [DONE]\n\n"))
    controller.close()
  }
}

export function promptLabEventToDelta(data: string): { content?: string; done?: boolean } {
  const parsed = parseJSON(data)
  if (parsed === undefined) return { content: data }
  if (typeof parsed === "string") return parsed === "[DONE]" ? { done: true } : { content: parsed }
  if (!isRecord(parsed)) return {}

  if (parsed.final === true || parsed.done === true || parsed.event === "final" || parsed.type === "final") return { done: true }
  const direct =
    stringValue(parsed.delta) ??
    stringValue(parsed.content) ??
    stringValue(parsed.text) ??
    stringValue(parsed.response) ??
    stringValue(parsed.output)
  if (direct) return { content: direct }

  if (typeof parsed.message === "string") return { content: parsed.message }
  if (isRecord(parsed.message)) {
    const message = stringValue(parsed.message.content) ?? stringValue(parsed.message.text)
    if (message) return { content: message }
  }
  if (Array.isArray(parsed.choices)) {
    const first = parsed.choices[0]
    if (isRecord(first) && isRecord(first.delta)) {
      const content = stringValue(first.delta.content)
      if (content) return { content }
    }
  }
  return {}
}

function sse(value: unknown): Uint8Array {
  return encoder.encode(`data: ${JSON.stringify(value)}\n\n`)
}

function parseJSON(input: string): unknown {
  try {
    return JSON.parse(input)
  } catch {
    return undefined
  }
}

function stringValue(input: unknown): string | undefined {
  return typeof input === "string" ? input : undefined
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input)
}
