import { decodeOpenAIModelID } from "./catalog"
import type { ModelSelection, OpenAIChatCompletionRequest, OpenAIChatMessage } from "./types"

const encoder = new TextEncoder()
const decoder = new TextDecoder()

export function messageContentToText(content: OpenAIChatMessage["content"]): string {
  if (typeof content === "string") return normalizePromptText(content)
  if (!content) return ""
  return content
    .map((part) => {
      if (part.type === "text" && typeof part.text === "string") return normalizePromptText(part.text)
      if (part.type === "image_url") return "[image]"
      return ""
    })
    .filter(Boolean)
    .join("\n")
}

function normalizePromptText(text: string): string {
  const trimmed = text.trim()
  if (!trimmed.startsWith('"') || !trimmed.endsWith('"')) return text
  const parsed = parseJSON(trimmed)
  return typeof parsed === "string" ? parsed : text
}

export function messagesToPromptText(messages: OpenAIChatMessage[]): string {
  return messages
    .map((message) => {
      if (message.role === "tool") {
        return `tool: ${messageContentToText(message.content)}`
      }
      if (message.role === "assistant") {
        const toolCalls = (message as Record<string, unknown>).tool_calls
        if (Array.isArray(toolCalls) && toolCalls.length > 0) {
          const lines = toolCalls.flatMap((c: unknown) => {
            if (!isRecord(c)) return []
            const fn = isRecord(c.function) ? c.function : c
            const name = stringValue(fn.name) ?? "unknown"
            const args = stringValue(fn.arguments) ?? "{}"
            return [`assistant: called ${name}(${args})`]
          })
          return lines.join("\n") || ""
        }
      }
      const text = messageContentToText(message.content)
      if (!text) return ""
      if (message.role === "user") return text
      return `${message.role}: ${text}`
    })
    .filter(Boolean)
    .join("\n\n")
}

export function toolInstructionText(tools: unknown[]): string {
  const descriptions = tools.flatMap((tool) => {
    if (!isRecord(tool)) return []
    const fn = isRecord(tool.function) ? tool.function : undefined
    if (!fn) return []
    const name = stringValue(fn.name)
    if (!name) return []
    const desc = stringValue(fn.description)
    const params = fn.parameters
    let line = `- ${name}`
    if (desc) line += `: ${desc}`
    if (isRecord(params) && isRecord(params.properties)) {
      const props = Object.entries(params.properties as Record<string, unknown>)
        .map(([k, v]) => {
          const propType = isRecord(v) ? stringValue(v.type) : undefined
          return propType ? `${k}: ${propType}` : k
        })
        .join(", ")
      if (props) line += ` (${props})`
    }
    return [line]
  })

  if (descriptions.length === 0) return ""

  return [
    "## Tool Call Protocol (CRITICAL — READ THIS FIRST)",
    "",
    "You are running inside a special tool bridge. Native function calling is NOT available. The ONLY way to call a tool is to output this exact XML block:",
    "<heelcode_tool_call>",
    '{"name": "TOOL_NAME", "arguments": {"param": "value"}}',
    "</heelcode_tool_call>",
    "",
    "CRITICAL RULES:",
    "1. To READ a local file → use the 'read' tool via the XML above. NEVER use web.run, webfetch, or web search to access local files — those cannot reach local paths.",
    "2. To SEARCH text in files → use grep. To LIST files → use glob. To RUN a command → use bash.",
    "3. Output the XML IMMEDIATELY when you need a tool — do NOT say 'I will now...' or 'Let me...' first.",
    "4. To call multiple tools, output multiple XML blocks one after another.",
    "5. Only output prose when giving the FINAL answer after all tool calls are complete.",
    "6. NEVER end a response by describing what you plan to do next — if you need a tool, OUTPUT the XML right now.",
    "",
    "Example — finding then reading a file:",
    "User: Summarize config.json.",
    "Assistant: <heelcode_tool_call>",
    '{"name": "read", "arguments": {"filePath": "config.json"}}',
    "</heelcode_tool_call>",
    "Tool result: {\"port\": 3000}",
    "Assistant: The config file sets port 3000.",
    "",
    "When instructions say 'make a tool call', output the XML above — NOT a JSON function call, NOT web.run, NOT a URL fetch.",
    "",
    "Available tools:",
    ...descriptions,
  ].join("\n")
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

  // Extract system/developer message and combine with tool instructions for promptPrefix.
  // PromptLab still injects its own system prompt, but promptPrefix adds our context on top.
  const systemMsg = request.messages.find((m) => m.role === "system" || m.role === "developer")
  const systemText = systemMsg ? messageContentToText(systemMsg.content) : ""
  const toolInstruction =
    Array.isArray(request.tools) && request.tools.length > 0 ? toolInstructionText(request.tools) : ""
  const promptPrefix = [systemText, toolInstruction].filter(Boolean).join("\n\n")

  // Build conversation text from non-system messages, including serialized tool calls and results.
  const conversationMessages = request.messages.filter((m) => m.role !== "system" && m.role !== "developer")
  const text = messagesToPromptText(conversationMessages) || lastUserText(request.messages)

  const temperature = promptLabTemperature(request.temperature, selection)

  const payload: Record<string, unknown> = {
    text,
    messageId: messageID,
    parentMessageId: parentMessageID,
    conversationId: conversationID,
    isCreatedByUser: true,
    endpointOption: selection.model,
    endpoint: selection.endpoint,
    model: selection.model,
    addedConvo: [],
    isTemporary: true,
    isRegenerate: false,
    isContinued: false,
    ephemeralAgent: false,
    manualSkills: [],
    promptPrefix,
    temperature,
    max_tokens: request.max_tokens ?? request.max_completion_tokens,
    top_p: request.top_p,
    stop: request.stop,
  }

  // Do not send native tools — PromptLab ignores them. Tool instructions are injected as text in promptPrefix.

  return payload
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

// Collects a PromptLab stream through the full SSE transformer (including XML tool-call
// extraction) and returns a non-streaming OpenAI response object with tool_calls if present.
export async function promptLabStreamToOpenAINonStreaming(
  input: ReadableStream<Uint8Array>,
  model: string,
): Promise<ReturnType<typeof openAINonStreamingResponse>> {
  const transformed = transformPromptLabSSEToOpenAI(input, model)
  const reader = transformed.getReader()
  const dec = new TextDecoder()
  let buf = ""
  let content = ""
  const toolCalls: OpenAIToolCallDelta[] = []
  let finishReason = "stop"
  let id: string | undefined

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      for (;;) {
        const nl = buf.search(/\r?\n\r?\n/)
        if (nl === -1) break
        const line = buf.slice(0, nl)
        buf = buf.slice(buf[nl] === "\r" ? nl + 4 : nl + 2)
        const raw = line
          .split(/\r?\n/)
          .filter((l) => l.startsWith("data:"))
          .map((l) => l.slice(5).trimStart())
          .join("")
        if (!raw || raw === "[DONE]") continue
        const chunk = parseJSON(raw)
        if (!isRecord(chunk)) continue
        if (typeof chunk.id === "string") id = chunk.id
        const choice = Array.isArray(chunk.choices) && isRecord(chunk.choices[0]) ? chunk.choices[0] : undefined
        if (!choice) continue
        const delta = isRecord(choice.delta) ? choice.delta : undefined
        if (delta) {
          if (typeof delta.content === "string") content += delta.content
          if (Array.isArray(delta.tool_calls)) toolCalls.push(...(delta.tool_calls as OpenAIToolCallDelta[]))
        }
        if (typeof choice.finish_reason === "string") finishReason = choice.finish_reason
      }
    }
  } finally {
    await reader.cancel().catch(() => {})
  }

  // emitStreamError always prefixes with "PromptLab " — propagate as an exception so the
  // server returns a 500 rather than a 200 with error text as the message content.
  if (!toolCalls.length && content.startsWith("PromptLab ")) {
    throw new Error(content)
  }

  if (toolCalls.length) {
    return {
      id: id ?? `chatcmpl-${crypto.randomUUID()}`,
      object: "chat.completion",
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [
        {
          index: 0,
          message: {
            role: "assistant",
            content: null,
            tool_calls: toolCalls.map((tc) => ({
              id: tc.id,
              type: tc.type ?? "function",
              function: tc.function,
            })),
          } as Record<string, unknown>,
          finish_reason: finishReason,
        },
      ],
    } as ReturnType<typeof openAINonStreamingResponse>
  }

  return openAINonStreamingResponse({ id, model, content, finishReason })
}

export function promptLabJSONToText(value: unknown): string {
  if (typeof value === "string") return value
  if (!isRecord(value)) return ""
  const direct =
    stringValue(value.text) ?? stringValue(value.content) ?? stringValue(value.response) ?? stringValue(value.output)
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

export type OpenAIToolCallDelta = {
  index: number
  id?: string
  type?: "function"
  function?: {
    name?: string
    arguments?: string
  }
}

type PromptLabDelta = {
  content?: string
  toolCalls?: OpenAIToolCallDelta[]
  done?: boolean
  error?: string
}

export function promptLabTemperature(temperature: number | undefined, selection: ModelSelection): number | undefined {
  if (
    temperature !== undefined &&
    temperature !== 1 &&
    selection.endpoint === "bedrock" &&
    selection.model.toLowerCase().includes("anthropic.claude")
  ) {
    return undefined
  }
  return temperature
}

export function openAIChunk(params: {
  id: string
  model: string
  content?: string
  toolCalls?: OpenAIToolCallDelta[]
  finishReason?: string
}) {
  return {
    id: params.id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model: params.model,
    choices: [
      {
        index: 0,
        delta: {
          ...(params.content === undefined ? {} : { content: params.content }),
          ...(params.toolCalls === undefined ? {} : { tool_calls: params.toolCalls }),
        },
        finish_reason: params.finishReason ?? null,
      },
    ],
  }
}

export function openAIToolCallStream(model: string, toolCall: OpenAIToolCallDelta): ReadableStream<Uint8Array> {
  const id = `chatcmpl-${crypto.randomUUID()}`
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(sse(openAIChunk({ id, model, toolCalls: [toolCall] })))
      controller.enqueue(sse(openAIChunk({ id, model, finishReason: "tool_calls" })))
      controller.enqueue(encoder.encode("data: [DONE]\n\n"))
      controller.close()
    },
  })
}

export function transformPromptLabSSEToOpenAI(
  input: ReadableStream<Uint8Array>,
  model: string,
): ReadableStream<Uint8Array> {
  const id = `chatcmpl-${crypto.randomUUID()}`
  let sseBuf = ""       // buffer for SSE event parsing
  let closed = false
  let sawContent = false   // received any content delta (used to skip re-emitted final text)
  let sawToolCalls = false // emitted tool calls to the client

  // Hybrid tool-call detection state.  We hold back up to (TOOL_OPEN.length - 1) characters
  // of streamed text in case they are the start of a tool call XML tag.  Once the full open
  // tag is confirmed in the buffer we stop streaming and accumulate the complete XML block.
  const TOOL_OPEN = "<heelcode_tool_call"
  let textPending = ""  // lookahead: might be prefix of tool call open tag
  let inToolCall = false
  let toolCallBuf = ""

  return new ReadableStream<Uint8Array>({
    async start(controller) {
      const reader = input.getReader()
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          sseBuf += decoder.decode(value, { stream: true })
          flushEvents(controller, id, model)
        }
        sseBuf += decoder.decode()
        flushEvents(controller, id, model, true)
        close(controller, id, model)
      } catch (error) {
        emitStreamError(controller, id, model, errorMessage(error))
      }
    },
  })

  function flushEvents(
    controller: ReadableStreamDefaultController<Uint8Array>,
    id: string,
    model: string,
    flush = false,
  ) {
    for (;;) {
      const split = sseBuf.search(/\r?\n\r?\n/)
      if (split === -1) {
        if (!flush || !sseBuf.trim()) return
        const event = sseBuf
        sseBuf = ""
        emitEvent(controller, id, model, event)
        return
      }
      const event = sseBuf.slice(0, split)
      sseBuf = sseBuf.slice(sseBuf[split] === "\r" ? split + 4 : split + 2)
      emitEvent(controller, id, model, event)
    }
  }

  function emitEvent(
    controller: ReadableStreamDefaultController<Uint8Array>,
    id: string,
    model: string,
    event: string,
  ) {
    if (closed) return
    const data = eventData(event)
    if (!data || data === "[DONE]") {
      if (data === "[DONE]") close(controller, id, model)
      return
    }

    const delta = promptLabEventToDelta(data, eventName(event))
    if (delta.error) {
      emitStreamError(controller, id, model, delta.error)
      return
    }
    // Native tool calls from the underlying model (e.g. if PromptLab ever forwards them).
    if (delta.toolCalls?.length) {
      sawToolCalls = true
      controller.enqueue(sse(openAIChunk({ id, model, toolCalls: delta.toolCalls })))
      if (delta.done) close(controller, id, model)
      return
    }
    if (delta.content) {
      // Skip final-event content if we already streamed it via intermediate chunks.
      const shouldProcess = !delta.done || !sawContent
      if (shouldProcess) {
        sawContent = true
        pushContent(controller, id, model, delta.content)
      }
      if (delta.done) close(controller, id, model)
      return
    }
    if (delta.done) {
      close(controller, id, model)
      return
    }
  }

  // Push a content token through the lookahead buffer.  Emits text immediately unless
  // the buffer ends with a prefix of the tool-call open tag (which could span chunks).
  function pushContent(
    controller: ReadableStreamDefaultController<Uint8Array>,
    id: string,
    model: string,
    text: string,
  ) {
    if (inToolCall) {
      toolCallBuf += text
      return
    }
    textPending += text

    // Fast path: full open tag is already in the buffer — switch to tool-call mode.
    const tagIdx = textPending.indexOf(TOOL_OPEN)
    if (tagIdx !== -1) {
      const before = textPending.slice(0, tagIdx)
      if (before) controller.enqueue(sse(openAIChunk({ id, model, content: before })))
      toolCallBuf = textPending.slice(tagIdx)
      textPending = ""
      inToolCall = true
      return
    }

    // Slow path: check if the rightmost '<' starts a prefix of TOOL_OPEN.
    // Only hold back chars that could still become the tag; emit everything else.
    const lastAngle = textPending.lastIndexOf("<")
    if (lastAngle !== -1 && TOOL_OPEN.startsWith(textPending.slice(lastAngle))) {
      if (lastAngle > 0) {
        controller.enqueue(sse(openAIChunk({ id, model, content: textPending.slice(0, lastAngle) })))
        textPending = textPending.slice(lastAngle)
      }
      return
    }

    // Nothing to hold back.
    if (textPending) {
      controller.enqueue(sse(openAIChunk({ id, model, content: textPending })))
      textPending = ""
    }
  }

  // Flush accumulated text and/or tool call XML to the client.  Called by close() before
  // emitting the finish chunk so sawToolCalls is set before the finishReason is decided.
  function flushTextBuffer(controller: ReadableStreamDefaultController<Uint8Array>, id: string, model: string) {
    if (inToolCall) {
      toolCallBuf += textPending
      textPending = ""
      const calls = extractToolCallsFromText(toolCallBuf)
      if (calls.length) {
        sawToolCalls = true
        controller.enqueue(sse(openAIChunk({ id, model, toolCalls: calls })))
      } else if (toolCallBuf.trim()) {
        // Malformed XML — emit as plain text so the turn isn't silently lost.
        controller.enqueue(sse(openAIChunk({ id, model, content: toolCallBuf })))
      }
      toolCallBuf = ""
      inToolCall = false
      return
    }
    if (textPending) {
      controller.enqueue(sse(openAIChunk({ id, model, content: textPending })))
      textPending = ""
    }
  }

  function close(controller: ReadableStreamDefaultController<Uint8Array>, id: string, model: string) {
    if (closed) return
    flushTextBuffer(controller, id, model)
    closed = true
    try {
      controller.enqueue(sse(openAIChunk({ id, model, finishReason: sawToolCalls ? "tool_calls" : "stop" })))
      controller.enqueue(encoder.encode("data: [DONE]\n\n"))
      controller.close()
    } catch {
      // The downstream client may have cancelled after PromptLab finished.
    }
  }

  function emitStreamError(
    controller: ReadableStreamDefaultController<Uint8Array>,
    id: string,
    model: string,
    error: string,
  ) {
    if (closed) return
    const message = error.startsWith("PromptLab ") ? error : `PromptLab stream error: ${error}`
    try {
      controller.enqueue(sse(openAIChunk({ id, model, content: message })))
    } catch {
      closed = true
      return
    }
    close(controller, id, model)
  }
}

export async function promptLabStreamToText(input: ReadableStream<Uint8Array>): Promise<string> {
  const reader = input.getReader()
  let buffer = ""
  let output = ""
  let sawText = false
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      output += drainText()
    }
    buffer += decoder.decode()
    output += drainText(true)
    return output
  } finally {
    await reader.cancel().catch(() => {})
  }

  function drainText(flush = false) {
    let text = ""
    for (;;) {
      const split = buffer.search(/\r?\n\r?\n/)
      if (split === -1) {
        if (!flush || !buffer.trim()) return text
        const event = buffer
        buffer = ""
        return text + eventText(event)
      }
      const event = buffer.slice(0, split)
      buffer = buffer.slice(buffer[split] === "\r" ? split + 4 : split + 2)
      text += eventText(event)
    }
  }

  function eventText(event: string) {
    const data = eventData(event)
    if (!data || data === "[DONE]") return ""
    const delta = promptLabEventToDelta(data, eventName(event))
    if (delta.error) throw new Error(`PromptLab stream error: ${delta.error}`)
    if (!delta.content) return ""
    if (delta.done && sawText) return ""
    sawText = true
    return delta.content
  }
}

export function promptLabEventToDelta(data: string, event?: string): PromptLabDelta {
  const parsed = parseJSON(data)
  if (parsed === undefined) return { content: data }
  if (typeof parsed === "string") return parsed === "[DONE]" ? { done: true } : { content: parsed }
  if (!isRecord(parsed)) return {}

  if (event === "error") return { error: promptLabErrorMessage(parsed) }
  const error = promptLabErrorMessage(parsed)
  if (error && ("error" in parsed || parsed.type === "error")) return { error }
  if (parsed.final === true || parsed.done === true || parsed.event === "final" || parsed.type === "final") {
    const final = promptLabFinalDelta(parsed)
    if (final) return { ...final, done: true }
    return { done: true }
  }
  if (parsed.created === true) return {}
  const toolCalls = promptLabToolCalls(parsed)
  if (toolCalls.length) return { toolCalls }
  const eventDelta = promptLabNestedDelta(parsed)
  if (eventDelta) return { content: eventDelta }
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

function promptLabErrorMessage(parsed: Record<string, unknown>) {
  const direct = stringValue(parsed.error) ?? stringValue(parsed.message) ?? stringValue(parsed.text)
  if (direct) return formatPromptLabError(direct)
  if (isRecord(parsed.error))
    return formatPromptLabError(stringValue(parsed.error.message) ?? JSON.stringify(parsed.error))
  return formatPromptLabError(JSON.stringify(parsed))
}

function formatPromptLabError(message: string) {
  const parsed = parseJSON(message)
  if (!isRecord(parsed)) return message
  if (parsed.type === "token_balance") {
    const balance = numberValue(parsed.balance)
    const tokenCost = numberValue(parsed.tokenCost)
    const promptTokens = numberValue(parsed.promptTokens)
    return [
      `PromptLab token balance is ${balance ?? 0}.`,
      tokenCost === undefined ? "" : `This metered model would cost ${formatNumber(tokenCost)} tokens.`,
      promptTokens === undefined ? "" : `Prompt tokens: ${formatNumber(promptTokens)}.`,
      "Switch to the unlimited PromptLab model: promptlab/azureOpenAI/gpt-5.4-mini (ChatGPT 5.4 mini), or wait for your monthly allocation to reset.",
    ]
      .filter(Boolean)
      .join(" ")
  }
  return message
}

function promptLabFinalDelta(parsed: Record<string, unknown>): PromptLabDelta | undefined {
  const message = isRecord(parsed.responseMessage)
    ? parsed.responseMessage
    : isRecord(parsed.message)
      ? parsed.message
      : undefined
  if (!message) return undefined

  const direct = stringValue(message.content) ?? stringValue(message.text) ?? stringValue(message.response)
  if (direct) return { content: direct }

  if (!Array.isArray(message.content)) return undefined
  const content: string[] = []
  for (const part of message.content) {
    if (!isRecord(part)) continue
    if (part.type === "error") return { error: promptLabErrorMessage(part) }
    const text = stringValue(part.text) ?? stringValue(part.content) ?? stringValue(part.message)
    if (text) content.push(text)
  }
  return content.length ? { content: content.join("") } : undefined
}

function promptLabNestedDelta(parsed: Record<string, unknown>): string | undefined {
  if (parsed.event !== "on_message_delta") return undefined
  if (!isRecord(parsed.data)) return undefined
  if (!isRecord(parsed.data.delta)) return undefined
  const content = parsed.data.delta.content
  if (!Array.isArray(content)) return undefined
  return content
    .map((part) => {
      if (!isRecord(part)) return ""
      return stringValue(part.text) ?? ""
    })
    .join("")
}

function promptLabToolCalls(parsed: Record<string, unknown>): OpenAIToolCallDelta[] {
  const openAI = openAIToolCallsFromRecord(parsed)
  if (openAI.length) return openAI
  if (!isRecord(parsed.data) || !isRecord(parsed.data.delta)) return []
  const nested = openAIToolCallsFromRecord(parsed.data.delta)
  if (nested.length) return nested
  const content = parsed.data.delta.content
  if (!Array.isArray(content)) return []
  return content.flatMap((part, index) => toolCallFromContentPart(part, index))
}

function openAIToolCallsFromRecord(value: Record<string, unknown>): OpenAIToolCallDelta[] {
  const direct = value.tool_calls ?? value.toolCalls
  if (Array.isArray(direct)) return direct.flatMap((item, index) => normalizeOpenAIToolCall(item, index))
  if (Array.isArray(value.choices)) {
    const first = value.choices[0]
    if (!isRecord(first) || !isRecord(first.delta)) return []
    const calls = first.delta.tool_calls ?? first.delta.toolCalls
    if (Array.isArray(calls)) return calls.flatMap((item, index) => normalizeOpenAIToolCall(item, index))
  }
  return []
}

function toolCallFromContentPart(part: unknown, index: number): OpenAIToolCallDelta[] {
  if (!isRecord(part)) return []
  const type = stringValue(part.type)
  if (type !== "tool_use" && type !== "tool_call" && type !== "function_call") return []
  return normalizeToolCall(part, index)
}

function normalizeToolCall(input: unknown, index: number): OpenAIToolCallDelta[] {
  if (!isRecord(input)) return []
  const fn = isRecord(input.function) ? input.function : input
  const name =
    stringValue(fn.name) ??
    stringValue(input.name) ??
    stringValue(input.tool_name) ??
    stringValue(input.toolName) ??
    stringValue(input.function_name) ??
    stringValue(input.functionName)
  if (!name) return []
  const args = fn.arguments ?? input.arguments ?? input.input ?? input.args ?? {}
  return [
    {
      index: typeof input.index === "number" ? input.index : index,
      id: stringValue(input.id),
      type: "function",
      function: {
        name,
        arguments: typeof args === "string" ? args : JSON.stringify(args),
      },
    },
  ]
}

function normalizeOpenAIToolCall(input: unknown, index: number): OpenAIToolCallDelta[] {
  if (!isRecord(input)) return []
  const fn = isRecord(input.function) ? input.function : undefined
  const result: OpenAIToolCallDelta = {
    index: typeof input.index === "number" ? input.index : index,
    id: stringValue(input.id),
    type: "function",
  }
  if (fn) {
    result.function = {}
    const name = stringValue(fn.name)
    const args = stringValue(fn.arguments)
    if (name !== undefined) result.function.name = name
    if (args !== undefined) result.function.arguments = args
  }
  return [result]
}

function extractToolCallsFromText(text: string): OpenAIToolCallDelta[] {
  const results: OpenAIToolCallDelta[] = []
  const re = /<heelcode_tool_call[^>]*>([\s\S]*?)<\/heelcode_tool_call>/g
  let match: RegExpExecArray | null
  let index = 0
  while ((match = re.exec(text)) !== null) {
    const inner = match[1].trim()
    const parsed = parseJSONLenient(inner)
    if (!isRecord(parsed)) continue
    const name = stringValue(parsed.name)
    if (!name) continue
    const rawArgs = parsed.arguments ?? {}
    const args = typeof rawArgs === "string" ? rawArgs : JSON.stringify(rawArgs)
    results.push({
      index: index++,
      id: `call_${crypto.randomUUID().replace(/-/g, "").slice(0, 24)}`,
      type: "function",
      function: { name, arguments: args },
    })
  }
  return results
}

// Parse JSON leniently: first try strict parse, then strip trailing garbage chars one at a time.
// Handles model output like `{"name":"foo"}}}` where extra braces are appended by mistake.
function parseJSONLenient(input: string): unknown {
  const strict = parseJSON(input)
  if (strict !== undefined) return strict
  // Walk backwards removing trailing non-whitespace chars until we get a parse.
  let trimmed = input.trimEnd()
  for (let i = trimmed.length - 1; i > 0; i--) {
    const candidate = trimmed.slice(0, i).trimEnd()
    if (!candidate.endsWith("}") && !candidate.endsWith("]")) continue
    const result = parseJSON(candidate)
    if (result !== undefined) return result
  }
  return undefined
}

function sse(value: unknown): Uint8Array {
  return encoder.encode(`data: ${JSON.stringify(value)}\n\n`)
}

function eventData(event: string) {
  return event
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n")
    .trim()
}

function eventName(event: string) {
  return event
    .split(/\r?\n/)
    .find((line) => line.startsWith("event:"))
    ?.slice(6)
    .trim()
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

function numberValue(input: unknown): number | undefined {
  return typeof input === "number" && Number.isFinite(input) ? input : undefined
}

function formatNumber(input: number) {
  return Number.isInteger(input) ? String(input) : input.toFixed(2)
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input)
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
