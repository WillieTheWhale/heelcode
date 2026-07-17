import type { Auth } from "@/auth"
import type { Provider } from "@/provider/provider"
import { ProviderTransform } from "@/provider/transform"
import { errorMessage } from "@/util/error"
import { isRecord } from "@/util/record"
import { asSchema, type ModelMessage, type Tool } from "ai"
import { Cause, Effect, FiberSet, Queue } from "effect"
import * as Stream from "effect/Stream"
import { FetchHttpClient } from "effect/unstable/http"
import {
  LLMEvent,
  LLMRequest,
  Tool as NativeTool,
  ToolFailure,
  ToolRuntime,
  toDefinitions,
  type JsonSchema,
} from "@opencode-ai/llm"
import type { LLMClientShape } from "@opencode-ai/llm/route"
import { LLMNative } from "./native-request"
import { PromptLabRuntime } from "./promptlab-runtime"

export type RuntimeStatus =
  | { readonly type: "supported"; readonly apiKey: string; readonly baseURL?: string }
  | { readonly type: "unsupported"; readonly reason: string }
export type StreamResult =
  | { readonly type: "supported"; readonly stream: Stream.Stream<LLMEvent, unknown> }
  | { readonly type: "unsupported"; readonly reason: string }

type StreamInput = {
  readonly sessionID?: string
  readonly small?: boolean
  readonly model: Provider.Model
  readonly provider: Provider.Info
  readonly auth: Auth.Info | undefined
  readonly llmClient: LLMClientShape
  readonly messages: ModelMessage[]
  readonly tools: Record<string, Tool>
  readonly toolChoice?: "auto" | "required" | "none"
  readonly temperature?: number
  readonly topP?: number
  readonly topK?: number
  readonly maxOutputTokens?: number
  readonly providerOptions?: Record<string, any>
  readonly headers: Record<string, string>
  readonly abort: AbortSignal
}

export function status(input: Pick<StreamInput, "model" | "provider" | "auth">): RuntimeStatus {
  return statusWithFetch(input, providerFetch(input))
}

function statusWithFetch(
  input: Pick<StreamInput, "model" | "provider" | "auth">,
  fetch: typeof globalThis.fetch | undefined,
): RuntimeStatus {
  const providerID = input.model.providerID
  if (
    providerID !== "openai" &&
    providerID !== "anthropic" &&
    providerID !== "promptlab" &&
    !providerID.startsWith("opencode")
  )
    return { type: "unsupported", reason: "provider is not openai, opencode, promptlab, or anthropic" }
  const npm = input.model.api.npm
  if (npm !== "@ai-sdk/openai" && npm !== "@ai-sdk/openai-compatible" && npm !== "@ai-sdk/anthropic")
    return { type: "unsupported", reason: "provider package is not OpenAI, OpenAI-compatible, or Anthropic" }
  if (input.auth?.type === "oauth" && !(input.provider.id === "openai" && fetch)) {
    return { type: "unsupported", reason: "OAuth auth requires a provider fetch override" }
  }

  const apiKey = typeof input.provider.options.apiKey === "string" ? input.provider.options.apiKey : input.provider.key
  if (!apiKey) return { type: "unsupported", reason: "API key is not configured" }

  return {
    type: "supported",
    apiKey,
    baseURL: typeof input.provider.options.baseURL === "string" ? input.provider.options.baseURL : undefined,
  }
}

export function stream(input: StreamInput): StreamResult {
  const fetch = providerFetch(input)
  const current = statusWithFetch(input, fetch)
  if (current.type === "unsupported") return current

  // Integration point with @opencode-ai/llm: native-request lowers session data
  // into an LLMRequest, then LLMClient handles route selection and transport.
  //
  // ProviderTransform.providerOptions builds AI-SDK-shaped options for the
  // selected SDK key (e.g. "openai") and the native LLM SDK reads the same
  // keys via OpenAIOptions.* (store, reasoningEffort, reasoningSummary,
  // include, textVerbosity, promptCacheKey). Both sides intentionally use
  // OpenAI's official wire field names, so this is identity, not translation
  // — if a field ever needs to differ between the two surfaces, the
  // translation belongs here, not split across both packages.
  const tools = nativeTools(
    input.model.providerID === "promptlab"
      ? Object.fromEntries(Object.entries(input.tools).filter(([name]) => promptLabTools.has(name)))
      : input.tools,
    input,
  )
  if (input.model.providerID === "promptlab") {
    if (!input.sessionID) return { type: "unsupported", reason: "PromptLab native inference requires a session ID" }
    return {
      ...current,
      stream: withToolDispatch(
        PromptLabRuntime.stream({
          sessionID: input.sessionID,
          inferenceScopeID: input.small
            ? `${input.sessionID}:advisory:${crypto.randomUUID()}`
            : `${input.sessionID}:primary`,
          transient: input.small,
          model: input.model.api.id,
          baseURL: current.baseURL,
          apiKey: current.apiKey,
          messages: input.messages,
          tools: toDefinitions(tools),
          toolChoice: input.toolChoice,
          temperature: input.temperature,
          topP: input.topP,
          maxOutputTokens: input.maxOutputTokens,
          abort: input.abort,
          fetch: promptLabFetch(input),
        }),
        tools,
      ),
    }
  }
  const request = LLMNative.request({
    model: input.model,
    apiKey: current.apiKey,
    baseURL: current.baseURL,
    messages: ProviderTransform.message(input.messages, input.model, input.providerOptions ?? {}),
    toolChoice: input.toolChoice,
    temperature: input.temperature,
    topP: input.topP,
    topK: input.topK,
    maxOutputTokens: input.maxOutputTokens,
    providerOptions: ProviderTransform.providerOptions(input.model, input.providerOptions ?? {}),
    headers: { ...providerHeaders(input.provider.options.headers), ...input.headers },
  })
  const stream = withToolDispatch(
    input.llmClient.stream(
      LLMRequest.update(request, {
        tools: [...request.tools, ...toDefinitions(tools)],
      }),
    ),
    tools,
  )

  return {
    ...current,
    stream: fetch ? stream.pipe(Stream.provideService(FetchHttpClient.Fetch, fetch)) : stream,
  }
}

const promptLabTools = new Set(["glob", "grep", "read", "edit", "write", "bash"])

function withToolDispatch(provider: Stream.Stream<LLMEvent, unknown>, tools: ReturnType<typeof nativeTools>) {
  return Stream.scoped(
    Stream.unwrap(
      Effect.gen(function* () {
        const settlements = yield* FiberSet.make<void>()
        const results = yield* Queue.unbounded<LLMEvent, Cause.Done>()
        const output = provider.pipe(
          Stream.flatMap((event) =>
            event.type !== "tool-call" || event.providerExecuted
              ? Stream.make(event)
              : Stream.make(event).pipe(
                  Stream.concat(
                    Stream.fromEffectDrain(
                      ToolRuntime.dispatch(tools, event).pipe(
                        Effect.flatMap((dispatched) => Queue.offerAll(results, dispatched.events)),
                        Effect.catchCause((cause) => Queue.failCause(results, cause)),
                        Effect.asVoid,
                        FiberSet.run(settlements, { startImmediately: true }),
                      ),
                    ),
                  ),
                ),
          ),
          Stream.concat(
            Stream.fromEffectDrain(
              FiberSet.awaitEmpty(settlements).pipe(Effect.andThen(Queue.end(results)), Effect.asVoid),
            ),
          ),
        )
        return output.pipe(Stream.concat(Stream.fromQueue(results)))
      }),
    ),
  )
}

function promptLabFetch(input: Pick<StreamInput, "provider">): typeof globalThis.fetch {
  const value: unknown = input.provider.options.fetch
  return typeof value === "function" ? (value as typeof globalThis.fetch) : globalThis.fetch
}

function providerFetch(input: Pick<StreamInput, "provider" | "auth">): typeof globalThis.fetch | undefined {
  if (input.provider.id !== "openai" || input.auth?.type !== "oauth") return undefined
  const value: unknown = input.provider.options.fetch
  if (typeof value !== "function") return undefined
  return value as typeof globalThis.fetch
}

function providerHeaders(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined
  return Object.fromEntries(
    Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === "string"),
  )
}

function nativeSchema(value: unknown): JsonSchema {
  if (!value || typeof value !== "object") return { type: "object", properties: {} }
  if ("jsonSchema" in value && value.jsonSchema && typeof value.jsonSchema === "object")
    return value.jsonSchema as JsonSchema
  return asSchema(value as Parameters<typeof asSchema>[0]).jsonSchema as JsonSchema
}

export function nativeTools(tools: Record<string, Tool>, input: Pick<StreamInput, "messages" | "abort">) {
  return Object.fromEntries(
    Object.entries(tools).map(([name, item]) => {
      const schema = asSchema(item.inputSchema)
      return [
        name,
        // Tool execution remains opencode-owned. The native runtime validates
        // the model-selected action before adapting it into the AI SDK Tool.execute shape.
        NativeTool.make({
          description: item.description ?? "",
          jsonSchema: nativeSchema(item.inputSchema),
          execute: (args: unknown, ctx) =>
            Effect.tryPromise({
              try: async () => {
                if (!item.execute) throw new Error(`Tool has no execute handler: ${name}`)
                const validated = schema.validate
                  ? await schema.validate(args)
                  : validateJsonSchema(await schema.jsonSchema, args)
                if (!validated.success) throw new Error(`Invalid tool input for ${name}: ${validated.error.message}`)
                return item.execute(validated.value, {
                  toolCallId: ctx?.id ?? name,
                  messages: input.messages,
                  abortSignal: input.abort,
                })
              },
              catch: (error) => new ToolFailure({ message: errorMessage(error), error }),
            }),
        }),
      ]
    }),
  )
}

function validateJsonSchema(
  schema: unknown,
  value: unknown,
): { success: true; value: unknown } | { success: false; error: Error } {
  const message = jsonSchemaError(schema, value, "$input")
  return message ? { success: false, error: new Error(message) } : { success: true, value }
}

function jsonSchemaError(schema: unknown, value: unknown, path: string): string | undefined {
  if (!isRecord(schema)) return `${path}: invalid JSON Schema`
  if (Array.isArray(schema.allOf)) {
    const error = schema.allOf.map((item) => jsonSchemaError(item, value, path)).find((item) => item)
    if (error) return error
  }
  if (Array.isArray(schema.anyOf) && !schema.anyOf.some((item) => !jsonSchemaError(item, value, path)))
    return `${path}: value did not match any allowed schema`
  if (Array.isArray(schema.oneOf) && schema.oneOf.filter((item) => !jsonSchemaError(item, value, path)).length !== 1)
    return `${path}: value must match exactly one allowed schema`
  if (Array.isArray(schema.enum) && !schema.enum.some((item) => Object.is(item, value)))
    return `${path}: value is not in the allowed enum`
  if ("const" in schema && !Object.is(schema.const, value)) return `${path}: value does not match const`
  const types = Array.isArray(schema.type) ? schema.type : typeof schema.type === "string" ? [schema.type] : []
  if (types.length && !types.some((type) => jsonType(type, value))) return `${path}: expected ${types.join(" or ")}`
  if ((types.includes("object") || isRecord(schema.properties)) && isRecord(value)) {
    const required = Array.isArray(schema.required)
      ? schema.required.filter((item): item is string => typeof item === "string")
      : []
    const missing = required.find((key) => !(key in value))
    if (missing) return `${path}.${missing}: required property is missing`
    const properties = isRecord(schema.properties) ? schema.properties : {}
    for (const [key, item] of Object.entries(value)) {
      if (key in properties) {
        const error = jsonSchemaError(properties[key], item, `${path}.${key}`)
        if (error) return error
        continue
      }
      if (schema.additionalProperties === false) return `${path}.${key}: additional property is not allowed`
      if (isRecord(schema.additionalProperties)) {
        const error = jsonSchemaError(schema.additionalProperties, item, `${path}.${key}`)
        if (error) return error
      }
    }
  }
  if ((types.includes("array") || schema.items !== undefined) && Array.isArray(value) && schema.items !== undefined) {
    const error = value
      .map((item, index) => jsonSchemaError(schema.items, item, `${path}[${index}]`))
      .find((item) => item)
    if (error) return error
  }
  if (typeof value === "string") {
    if (typeof schema.minLength === "number" && value.length < schema.minLength) return `${path}: string is too short`
    if (typeof schema.maxLength === "number" && value.length > schema.maxLength) return `${path}: string is too long`
    if (typeof schema.pattern === "string" && !new RegExp(schema.pattern).test(value))
      return `${path}: string does not match pattern`
  }
  if (typeof value === "number") {
    if (typeof schema.minimum === "number" && value < schema.minimum) return `${path}: number is below minimum`
    if (typeof schema.maximum === "number" && value > schema.maximum) return `${path}: number is above maximum`
  }
  return undefined
}

function jsonType(type: unknown, value: unknown) {
  if (type === "null") return value === null
  if (type === "object") return isRecord(value)
  if (type === "array") return Array.isArray(value)
  if (type === "integer") return typeof value === "number" && Number.isInteger(value)
  if (type === "number") return typeof value === "number" && Number.isFinite(value)
  return typeof value === type
}

export * as LLMNativeRuntime from "./native-runtime"
