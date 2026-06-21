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
  const toolInstruction = toolInstructionFromRequest(request)
  const text = toolInstruction
    ? [messagesToPromptText(request.messages), toolInstruction].filter(Boolean).join("\n\n")
    : lastUserText(request.messages)
  const messages = toolInstruction ? insertToolInstruction(request.messages, toolInstruction) : request.messages
  const temperature = promptLabTemperature(request.temperature, selection)

  return {
    userMessage: text,
    endpointOption: selection.model,
    endpoint: selection.endpoint,
    model: selection.model,
    messages,
    text,
    prompt: text,
    addedConvo: [],
    conversationId: conversationID,
    parentMessageId: parentMessageID,
    messageId: messageID,
    isTemporary: true,
    isRegenerate: false,
    isContinued: false,
    ephemeralAgent: false,
    manualSkills: [],
    temperature,
    max_tokens: request.max_tokens ?? request.max_completion_tokens,
    top_p: request.top_p,
    stop: request.stop,
    tools: request.tools,
    tool_choice: request.tool_choice,
  }
}

export function selectionFromRequest(request: OpenAIChatCompletionRequest): ModelSelection {
  const selection = decodeOpenAIModelID(request.model)
  if (!selection) {
    throw new Error(`Expected PromptLab model id in the form promptlab/<endpoint>/<model>, got ${request.model}`)
  }
  return selection
}

export function toolInstructionFromRequest(request: OpenAIChatCompletionRequest): string | undefined {
  if (!Array.isArray(request.tools) || request.tools.length === 0) return undefined
  if (!shouldExposeToolProtocol(request)) return undefined
  const hasToolResult = request.messages.some((message) => message.role === "tool")
  const tools = request.tools.flatMap((tool) => normalizePromptTool(tool))
  if (tools.length === 0) return undefined
  return [
    "Heelcode local tools are available, but this backend cannot call them directly.",
    "If the user asks to inspect local files, list directories, read files, search, edit, run commands, or otherwise use the local workspace, you must call exactly one appropriate tool before answering.",
    "Never claim that you inspected the local workspace unless you emitted a tool call.",
    hasToolResult
      ? "The conversation already includes local tool results. Use those results to answer the user's request now. Do not call another tool unless the result is insufficient."
      : "",
    "When a tool is needed, respond with only one XML tag in this exact format:",
    '<heelcode_tool_call>{"name":"tool_name","arguments":{}}</heelcode_tool_call>',
    "Do not include markdown, prose, or code fences around the tag.",
    "After a tool result is provided in the conversation, continue normally or request another tool.",
    `Available tools: ${JSON.stringify(tools)}`,
    request.tool_choice ? `Requested tool choice: ${JSON.stringify(request.tool_choice)}` : "",
  ]
    .filter(Boolean)
    .join("\n")
}

function insertToolInstruction(messages: OpenAIChatMessage[], instruction: string): OpenAIChatMessage[] {
  const lastUser = messages.findLastIndex((message) => message.role === "user")
  const toolMessage = { role: "developer" as const, content: instruction }
  if (lastUser === -1) return [...messages, toolMessage]
  return [...messages.slice(0, lastUser), toolMessage, ...messages.slice(lastUser)]
}

function shouldExposeToolProtocol(request: OpenAIChatCompletionRequest): boolean {
  if (request.tool_choice === "none") return false
  if (request.tool_choice !== undefined && request.tool_choice !== "auto") return true
  if (request.messages.some((message) => message.role === "tool")) return true
  return userTextLikelyNeedsLocalTool(lastUserText(request.messages))
}

function userTextLikelyNeedsLocalTool(text: string): boolean {
  if (/\b(use|call|invoke)\b[\s\S]{0,80}\btool\b/i.test(text)) return true
  if (/\b(glob|grep|read|bash)\b[\s\S]{0,40}\btool\b/i.test(text)) return true
  const action = /\b(inspect|list|read|search|find|grep|glob|edit|write|run|execute|open)\b/i.test(text)
  const target =
    /\b(file|files|directory|folder|repo|repository|workspace|codebase|project|shell|command|terminal|path|pattern)\b/i.test(
      text,
    )
  return action && (target || matchFilePath(text) !== undefined)
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

function promptLabTemperature(temperature: number | undefined, selection: ModelSelection): number | undefined {
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

export function preflightToolCallFromRequest(request: OpenAIChatCompletionRequest): OpenAIToolCallDelta | undefined {
  if (!Array.isArray(request.tools) || request.tools.length === 0) return undefined
  if (request.messages.some((message) => message.role === "tool")) return undefined
  const text = lastUserText(request.messages)
  const explicitToolRequest = /\b(use|call|invoke)\b/i.test(text)
  if (!explicitToolRequest && !userTextLikelyNeedsLocalTool(text)) return undefined
  const tools = request.tools.flatMap((tool) => normalizePromptTool(tool)) as Array<{
    name?: string
    parameters?: { required?: string[] }
  }>
  for (const tool of tools) {
    if (!tool.name) continue
    const named = new RegExp(`\\b${escapeRegExp(tool.name)}\\b`, "i").test(text)
    if (explicitToolRequest && !named) continue
    const args = inferToolArguments(tool.name, text)
    if (!args) continue
    return {
      index: 0,
      id: `call_${crypto.randomUUID().replaceAll("-", "").slice(0, 24)}`,
      type: "function",
      function: {
        name: tool.name,
        arguments: JSON.stringify(args),
      },
    }
  }
  return undefined
}

export function transformPromptLabSSEToOpenAI(
  input: ReadableStream<Uint8Array>,
  model: string,
  options: { tools?: unknown[] } = {},
): ReadableStream<Uint8Array> {
  const id = `chatcmpl-${crypto.randomUUID()}`
  let buffer = ""
  let closed = false
  let sawContent = false
  let sawToolCalls = false
  let syntheticBuffer = ""
  const useSyntheticToolProtocol = Array.isArray(options.tools) && options.tools.length > 0

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
    if (delta.toolCalls?.length) {
      sawToolCalls = true
      controller.enqueue(sse(openAIChunk({ id, model, toolCalls: delta.toolCalls })))
      if (delta.done) close(controller, id, model)
      return
    }
    if (delta.content) {
      const shouldEmitContent = !delta.done || !sawContent
      if (shouldEmitContent) {
        sawContent = true
        if (useSyntheticToolProtocol) syntheticBuffer += delta.content
        else controller.enqueue(sse(openAIChunk({ id, model, content: delta.content })))
      }
      if (delta.done) close(controller, id, model)
      return
    }
    if (delta.done) {
      close(controller, id, model)
      return
    }
  }

  function close(controller: ReadableStreamDefaultController<Uint8Array>, id: string, model: string) {
    if (closed) return
    closed = true
    try {
      if (useSyntheticToolProtocol && !sawToolCalls) {
        const toolCall = syntheticToolCallFromText(syntheticBuffer)
        if (toolCall) {
          sawToolCalls = true
          controller.enqueue(sse(openAIChunk({ id, model, toolCalls: [toolCall] })))
        } else if (syntheticBuffer) {
          controller.enqueue(sse(openAIChunk({ id, model, content: syntheticBuffer })))
        }
      }
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
    if (useSyntheticToolProtocol) syntheticBuffer += message
    else {
      sawContent = true
      try {
        controller.enqueue(sse(openAIChunk({ id, model, content: message })))
      } catch {
        closed = true
        return
      }
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

function syntheticToolCallFromText(text: string): OpenAIToolCallDelta | undefined {
  const raw = extractSyntheticToolCallJSON(text)
  if (!raw) return undefined
  const parsed = parseJSON(raw)
  const call = Array.isArray(parsed) ? parsed[0] : parsed
  if (!isRecord(call)) return undefined
  const fn = isRecord(call.function) ? call.function : call
  const name = stringValue(fn.name) ?? stringValue(call.name) ?? stringValue(call.tool) ?? stringValue(call.tool_name)
  if (!name) return undefined
  const args = fn.arguments ?? call.arguments ?? call.input ?? call.args ?? {}
  return {
    index: 0,
    id: `call_${crypto.randomUUID().replaceAll("-", "").slice(0, 24)}`,
    type: "function",
    function: {
      name,
      arguments: typeof args === "string" ? args : JSON.stringify(args),
    },
  }
}

function extractSyntheticToolCallJSON(text: string): string | undefined {
  const tagged = text.match(/<heelcode_tool_call>\s*([\s\S]*?)\s*<\/heelcode_tool_call>/i)
  if (tagged) return stripCodeFence(tagged[1].trim())
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)
  if (fenced) return stripCodeFence(fenced[1].trim())
  const trimmed = text.trim()
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
  return undefined
}

function stripCodeFence(text: string): string {
  return text
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim()
}

function normalizePromptTool(input: unknown): unknown[] {
  if (!isRecord(input)) return []
  const fn = isRecord(input.function) ? input.function : input
  const name = stringValue(fn.name) ?? stringValue(input.name)
  if (!name) return []
  return [
    {
      name,
      description: truncateToolText(stringValue(fn.description) ?? stringValue(input.description) ?? "", 260),
      parameters: summarizeParameters(fn.parameters ?? input.parameters ?? {}),
    },
  ]
}

function inferToolArguments(name: string, text: string): Record<string, unknown> | undefined {
  if (name === "glob") {
    const pattern =
      matchValue(text, "pattern") ??
      (/\b(list|inspect|show|find)\b[\s\S]{0,80}\b(files?|directories|folders?|entries|repo|repository|workspace|project|current directory|top[- ]level)\b/i.test(
        text,
      )
        ? "*"
        : undefined)
    if (pattern) return { pattern }
  }
  if (name === "grep") {
    const pattern = matchValue(text, "pattern") ?? matchValue(text, "search") ?? matchSearchTerm(text)
    if (pattern) return { pattern }
  }
  if (name === "read") {
    const filePath = matchValue(text, "file") ?? matchValue(text, "path") ?? matchFilePath(text)
    if (filePath) return { filePath }
  }
  if (name === "bash") {
    const command = matchQuotedAfter(text, "command")
    if (command) return { command, description: "Runs requested shell command" }
  }
  return undefined
}

function matchValue(text: string, label: string): string | undefined {
  const value =
    matchQuotedAfter(text, label) ??
    text.match(new RegExp(`\\b${escapeRegExp(label)}\\s+(?:is\\s+)?([^\\s,]+)`, "i"))?.[1]
  return value?.replace(/[.;:]+$/, "")
}

function matchQuotedAfter(text: string, label: string): string | undefined {
  const match = text.match(new RegExp(`\\b${escapeRegExp(label)}\\s+(?:is\\s+)?["'\`]([^"'\`]+)["'\`]`, "i"))
  return match?.[1]
}

function matchSearchTerm(text: string): string | undefined {
  return (
    matchQuotedAfter(text, "for") ?? text.match(/\b(?:search|grep|find)\s+(?:for\s+)?([^.,\s][^,.]*)/i)?.[1]?.trim()
  )
}

function matchFilePath(text: string): string | undefined {
  return text.match(/\b(?:[\w.-]+\/)*[\w.-]+\.[A-Za-z0-9]{1,12}\b/)?.[0]
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}

function summarizeParameters(input: unknown): unknown {
  if (!isRecord(input)) return {}
  const properties = isRecord(input.properties) ? input.properties : {}
  const required = Array.isArray(input.required) ? input.required.filter((item) => typeof item === "string") : []
  return {
    type: stringValue(input.type) ?? "object",
    properties: Object.fromEntries(
      Object.entries(properties).flatMap(([key, value]) => {
        if (!isRecord(value)) return [[key, {}]]
        return [
          [
            key,
            {
              type: stringValue(value.type) ?? "string",
              description: truncateToolText(stringValue(value.description) ?? "", 180),
            },
          ],
        ]
      }),
    ),
    required,
  }
}

function truncateToolText(input: string, max: number): string {
  const text = input.replace(/\s+/g, " ").trim()
  if (text.length <= max) return text
  return `${text.slice(0, max - 3)}...`
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
