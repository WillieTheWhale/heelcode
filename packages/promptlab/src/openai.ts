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
  const temperature = promptLabTemperature(request.temperature, selection)

  return {
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
    temperature,
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

export function toolInstructionFromRequest(request: OpenAIChatCompletionRequest): string | undefined {
  if (!Array.isArray(request.tools) || request.tools.length === 0) return undefined
  if (!shouldExposeToolProtocol(request)) return undefined
  const hasToolResult = request.messages.some((message) => message.role === "tool")
  const tools = request.tools.flatMap((tool) => normalizePromptTool(tool))
  if (tools.length === 0) return undefined
  return [
    "Heelcode local opencode tools are available. PromptLab cannot execute them directly.",
    "When you emit the XML tool-call intent below, Heelcode converts it into an OpenAI-compatible tool_call and opencode executes the tool locally.",
    "You are operating inside an iterative agent harness, not a one-shot chatbot.",
    "If the task involves files, code, repositories, terminals, tests, diagnostics, edits, or delegation, you must call exactly one appropriate tool before answering.",
    "Never claim that you inspected the local workspace unless you emitted a tool call.",
    hasToolResult
      ? "The conversation already includes local tool results. Use them to decide the next step. If more information or action is needed, call exactly one next tool. If the task is complete, answer concisely. Do not ask the user what to do next when you can continue with another tool."
      : "For non-trivial software tasks, start by inspecting the workspace with glob, grep, read, task, or another suitable tool.",
    'Subagent handoff happens through the local task tool. For task calls, include "description", "prompt", and "subagent_type".',
    "When a tool is needed, respond with only one XML tag in this exact format:",
    '<heelcode_tool_call>{"name":"tool_name","arguments":{}}</heelcode_tool_call>',
    "Do not include markdown, prose, or code fences around the tag.",
    "After a tool result is provided in the conversation, continue the loop by either requesting one next tool or giving the final answer.",
    `Available tools: ${JSON.stringify(tools)}`,
    request.tool_choice ? `Requested tool choice: ${JSON.stringify(request.tool_choice)}` : "",
  ]
    .filter(Boolean)
    .join("\n")
}

function shouldExposeToolProtocol(request: OpenAIChatCompletionRequest): boolean {
  if (request.tool_choice === "none") return false
  return true
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
  const tools = request.tools.flatMap((tool) => normalizePromptTool(tool))
  const sequencedToolCall = preflightSequencedToolCallFromRequest(request, tools)
  if (sequencedToolCall) return sequencedToolCall
  if (request.messages.some((message) => message.role === "tool")) return undefined
  const text = lastUserText(request.messages)
  const explicitToolRequest = /\b(use|call|invoke)\b/i.test(text)
  if (!explicitToolRequest && !userTextLikelyNeedsLocalTool(text)) return undefined
  const preferredTools = tools.filter((tool) => explicitlyNamesTool(text, tool.name))
  const candidateTools = preferredTools.length ? preferredTools : tools
  for (const tool of candidateTools) {
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

function explicitlyNamesTool(text: string, name: string): boolean {
  return (
    new RegExp(`\\b${escapeRegExp(name)}\\s+tool\\b`, "i").test(text) ||
    new RegExp(`\\b(use|call|invoke)\\b[\\s\\S]{0,80}\\b${escapeRegExp(name)}\\b[\\s\\S]{0,40}\\btool\\b`, "i").test(
      text,
    )
  )
}

function preflightSequencedToolCallFromRequest(
  request: OpenAIChatCompletionRequest,
  tools: PromptToolSummary[],
): OpenAIToolCallDelta | undefined {
  const toolResults = request.messages.filter((message) => message.role === "tool")
  if (toolResults.length === 0) return undefined
  const sequence = requestedToolSequenceFromRequest(request, tools)
  if (sequence.length < 2) return undefined
  const nextTool = sequence[toolResults.length]
  if (!nextTool || !tools.some((tool) => tool.name === nextTool)) return undefined
  const args =
    inferToolArgumentsFromConversation(nextTool, request) ??
    inferToolArguments(nextTool, lastUserText(request.messages))
  if (!args) return undefined
  return {
    index: 0,
    id: `call_${crypto.randomUUID().replaceAll("-", "").slice(0, 24)}`,
    type: "function",
    function: {
      name: nextTool,
      arguments: JSON.stringify(args),
    },
  }
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
        const toolCall = syntheticToolCallFromText(syntheticBuffer, options.tools)
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

function syntheticToolCallFromText(text: string, toolsInput: unknown[] | undefined): OpenAIToolCallDelta | undefined {
  const raw = extractSyntheticToolCallJSON(text)
  if (raw) {
    const parsed = parseJSON(raw)
    const call = Array.isArray(parsed) ? parsed[0] : parsed
    if (isRecord(call)) return syntheticToolCallFromRecord(call)
  }
  return syntheticToolCallFromProse(text, toolsInput)
}

function syntheticToolCallFromRecord(call: Record<string, unknown>): OpenAIToolCallDelta | undefined {
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

function syntheticToolCallFromProse(text: string, toolsInput: unknown[] | undefined): OpenAIToolCallDelta | undefined {
  const tools = (toolsInput ?? []).flatMap((tool) => normalizePromptTool(tool))
  if (!tools.length) return undefined
  if (!/\b(use|call|invoke|run|read|search|grep|glob|list|inspect|open|locate|delegate|subagent|task)\b/i.test(text)) {
    return undefined
  }

  if (/\b(task\s+tool|delegate|subagent|handoff)\b/i.test(text) && tools.some((tool) => tool.name === "task")) {
    const args = inferToolArguments("task", text)
    if (args) return syntheticToolCallFromRecord({ name: "task", arguments: args })
  }

  for (const tool of tools) {
    if (!new RegExp(`\\b${escapeRegExp(tool.name)}\\b`, "i").test(text)) continue
    const args = inferToolArguments(tool.name, text)
    if (!args) continue
    return syntheticToolCallFromRecord({ name: tool.name, arguments: args })
  }

  for (const name of ["read", "grep", "glob", "task"]) {
    if (!tools.some((tool) => tool.name === name)) continue
    const args = inferToolArguments(name, text)
    if (!args) continue
    return syntheticToolCallFromRecord({ name, arguments: args })
  }

  return undefined
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

type PromptToolSummary = {
  name: string
  description: string
  parameters: unknown
}

function normalizePromptTool(input: unknown): PromptToolSummary[] {
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
    const pattern =
      matchValue(text, "pattern") ?? matchValue(text, "search") ?? matchDefinitionTarget(text) ?? matchSearchTerm(text)
    if (pattern) return { pattern }
  }
  if (name === "read") {
    const filePath = matchPathValue(text, "file") ?? matchPathValue(text, "path") ?? matchFilePath(text)
    if (filePath) return { filePath }
  }
  if (name === "bash") {
    const command = matchQuotedAfter(text, "command") ?? matchBacktickedCommand(text)
    if (command) return { command, description: "Runs requested shell command" }
  }
  if (name === "task") {
    const args = inferTaskArguments(text)
    if (args) return args
  }
  return undefined
}

function inferToolArgumentsFromConversation(
  name: string,
  request: OpenAIChatCompletionRequest,
): Record<string, unknown> | undefined {
  const latestToolText = [...request.messages].reverse().find((message) => message.role === "tool")?.content
  const text = latestToolText === undefined ? "" : messageContentToText(latestToolText)
  if (name === "read") {
    const target =
      matchDefinitionTarget(lastUserText(request.messages)) ?? matchSearchTerm(lastUserText(request.messages))
    const filePath = (target ? matchDefinitionPathInText(text, target) : undefined) ?? matchPathInText(text)
    if (filePath) return { filePath }
  }
  if (name === "grep") {
    const pattern =
      matchDefinitionTarget(lastUserText(request.messages)) ?? matchSearchTerm(lastUserText(request.messages))
    if (pattern) return { pattern }
  }
  return undefined
}

function requestedToolSequenceFromRequest(request: OpenAIChatCompletionRequest, tools: PromptToolSummary[]): string[] {
  const text = lastUserText(request.messages)
  const matches = tools
    .map((tool) => ({
      name: tool.name,
      index: text.search(new RegExp(`\\b${escapeRegExp(tool.name)}\\b`, "i")),
    }))
    .filter((match) => match.index >= 0)
    .sort((a, b) => a.index - b.index)

  const seen = new Set<string>()
  return matches.flatMap((match) => {
    if (seen.has(match.name)) return []
    seen.add(match.name)
    return [match.name]
  })
}

function matchValue(text: string, label: string): string | undefined {
  const value =
    matchQuotedAfter(text, label) ??
    text.match(new RegExp(`\\b${escapeRegExp(label)}\\b\\s*(?:(?:is|=|:)\\s*)?([^\\s,]+)`, "i"))?.[1]
  return cleanScalarValue(value)
}

function matchQuotedAfter(text: string, label: string): string | undefined {
  const match = text.match(new RegExp(`\\b${escapeRegExp(label)}\\b\\s*(?:(?:is|=|:)\\s*)?["'\`]([^"'\`]+)["'\`]`, "i"))
  return match?.[1]
}

function matchBacktickedCommand(text: string): string | undefined {
  return text.match(/\b(?:run|execute)\b[\s\S]{0,40}`([^`]+)`/i)?.[1]
}

function matchPathValue(text: string, label: string): string | undefined {
  const quoted = matchQuotedAfter(text, label)
  const quotedPath = cleanPathValue(quoted)
  if (quotedPath) return quotedPath
  const value = matchValue(text, label)
  const path = cleanPathValue(value)
  if (path) return path
  return undefined
}

function inferTaskArguments(text: string): Record<string, unknown> | undefined {
  if (!/\b(task\s+tool|delegate|subagent|handoff)\b/i.test(text)) return undefined
  const subagent =
    matchValue(text, "subagent_type") ??
    matchValue(text, "subagent") ??
    matchValue(text, "agent") ??
    (/\bexplore\b/i.test(text) ? "explore" : undefined) ??
    (/\bgeneral\b/i.test(text) ? "general" : undefined) ??
    (/\bscout\b/i.test(text) ? "scout" : undefined)
  const prompt = matchQuotedAfter(text, "prompt") ?? matchQuotedAfter(text, "task")
  if (!subagent || !prompt) return undefined
  return {
    description: matchQuotedAfter(text, "description") ?? summarizeTaskPrompt(prompt),
    prompt,
    subagent_type: subagent,
  }
}

function summarizeTaskPrompt(prompt: string): string {
  return prompt.replace(/\s+/g, " ").trim().split(/\s+/).slice(0, 5).join(" ")
}

function matchSearchTerm(text: string): string | undefined {
  return (
    matchQuotedAfter(text, "for") ??
    cleanScalarValue(text.match(/\b(?:search|grep|find|locate)\s+(?:for\s+)?([A-Za-z_$][\w$.-]*)\b/i)?.[1])
  )
}

function matchDefinitionTarget(text: string): string | undefined {
  return (
    cleanScalarValue(text.match(/\bwhere\s+([A-Za-z_$][\w$.-]*)\s+(?:is|are)\s+defined\b/i)?.[1]) ??
    cleanScalarValue(
      text.match(/\b(?:find|locate|search for|grep for)\s+([A-Za-z_$][\w$.-]*)\b[\s\S]{0,60}\bdefinition\b/i)?.[1],
    ) ??
    cleanScalarValue(text.match(/\b([A-Za-z_$][\w$.-]*)\s+(?:is|are)\s+defined\b/i)?.[1])
  )
}

function matchFilePath(text: string): string | undefined {
  return text.match(/\b(?:[\w.-]+\/)*[\w.-]+\.[A-Za-z0-9]{1,12}\b/)?.[0]
}

function matchPathInText(text: string): string | undefined {
  return cleanPathValue(
    text.match(/(?:\/[^\s:]+|(?:[\w.-]+\/)+[\w.-]+\.[A-Za-z0-9]{1,12}|[\w.-]+\.[A-Za-z0-9]{1,12})/)?.[0],
  )
}

function matchDefinitionPathInText(text: string, target: string): string | undefined {
  let currentPath: string | undefined
  const candidates: string[] = []
  for (const line of text.split(/\r?\n/)) {
    const pathOnly = cleanPathValue(
      line.match(/^\s*((?:\/[^\s:]+|(?:[\w.-]+\/)+[\w.-]+\.[A-Za-z0-9]{1,12}|[\w.-]+\.[A-Za-z0-9]{1,12})):\s*$/)?.[1],
    )
    if (pathOnly) {
      currentPath = pathOnly
      continue
    }

    const inline = line.match(
      /^\s*((?:\/[^\s:]+|(?:[\w.-]+\/)+[\w.-]+\.[A-Za-z0-9]{1,12}|[\w.-]+\.[A-Za-z0-9]{1,12})):\s*(.*)$/,
    )
    const inlinePath = cleanPathValue(inline?.[1])
    const content = inline?.[2] ?? line
    const path = inlinePath ?? currentPath
    if (!path) continue
    if (!new RegExp(`\\b${escapeRegExp(target)}\\b`).test(content)) continue
    if (!isDefinitionLineForTarget(content, target)) continue
    candidates.push(path)
  }
  return candidates.find((path) => !isTestPath(path)) ?? candidates[0]
}

function isDefinitionLineForTarget(line: string, target: string): boolean {
  const name = escapeRegExp(target)
  return new RegExp(
    [
      `\\bexport\\s+(?:async\\s+)?function\\s+${name}\\b`,
      `\\b(?:async\\s+)?function\\s+${name}\\b`,
      `\\bexport\\s+(?:const|let|var|class|interface|type|enum)\\s+${name}\\b`,
      `\\b(?:const|let|var|class|interface|type|enum)\\s+${name}\\b`,
    ].join("|"),
  ).test(line)
}

function isTestPath(path: string): boolean {
  return /(?:^|\/)(?:__tests__|test|tests|spec)(?:\/|$)|\.(?:test|spec)\.[^/]+$/i.test(path)
}

function isLikelyPath(value: string): boolean {
  return /^(?:\/|\.\.?\/|~\/)/.test(value) || value.includes("/") || /\.[A-Za-z0-9]{1,12}$/.test(value)
}

function cleanPathValue(value: string | undefined): string | undefined {
  const cleaned = value
    ?.trim()
    .replace(/<[^>]*>.*$/, "")
    .replace(/[),;:]+$/, "")
  if (/[<>]/.test(cleaned ?? "")) return undefined
  if (!cleaned || !isLikelyPath(cleaned)) return undefined
  return cleaned
}

function cleanScalarValue(value: string | undefined): string | undefined {
  const cleaned = value?.trim().replace(/[.;:]+$/, "")
  if (!cleaned || /^(with|to|for|from|and|or|the|a|an|where|how|before|after|answer|answering)$/i.test(cleaned)) {
    return undefined
  }
  return cleaned
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
