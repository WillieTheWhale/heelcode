# Session LLM Runtime Boundaries

`../llm.ts` is the opencode session LLM service. It owns opencode concerns: auth, config, model/provider resolution, plugins, permissions, telemetry headers, and runtime selection. It is the only file in this area that should know about the full session request shape.

This folder contains adapters behind that service boundary:

- `ai-sdk.ts` converts AI SDK `fullStream` parts into `@opencode-ai/llm` `LLMEvent`s. This is the default runtime path.
- `native-request.ts` converts opencode's normalized session input into a native `@opencode-ai/llm` `LLMRequest`. It does not execute requests.
- `native-runtime.ts` is the opt-in native runtime adapter. It decides whether a selected model is supported, builds the native request, bridges opencode tools into native executable tools, and delegates transport to `LLMClient` / `RequestExecutor`.
- `promptlab-runtime.ts` is the fail-closed PromptLab exception: it calls the loopback native inference endpoint and maps SSE directly to `LLMEvent`s without the legacy OpenAI compatibility route.

## File Structure

```txt
src/session/
  llm.ts                    session-owned orchestration and runtime selection
  llm/
    AGENTS.md               boundary notes for the adapter layer
    ai-sdk.ts               AI SDK fullStream -> @opencode-ai/llm LLMEvent adapter
    native-request.ts       opencode/AI SDK-shaped input -> @opencode-ai/llm LLMRequest
    native-runtime.ts       native runtime gate, tool bridge, and LLMClient handoff
    promptlab-runtime.ts    PromptLab native SSE -> @opencode-ai/llm LLMEvent transport
```

Integration points:

- `../llm.ts` imports `LLMClient` from `@opencode-ai/llm/route`; native execution is the only path that calls it directly.
- `../llm.ts` imports `LLMAISDK` from `./llm/ai-sdk`; the AI SDK path still calls `streamText(...)` locally, then adapts `result.fullStream` into shared `LLMEvent`s.
- `../llm.ts` imports `LLMNativeRuntime` from `./llm/native-runtime`; this is the runtime-selection seam. Unsupported native requests return a reason and fall back to AI SDK.
- `native-runtime.ts` imports `LLMNative` from `./native-request`; this keeps request lowering separate from transport and tool execution.
- PromptLab models always use `promptlab-runtime.ts`, even when the general native-runtime experiment is disabled. They fail closed if the endpoint is unavailable and never fall back to AI SDK/XML compatibility.
- `native-request.ts` is the only adapter file that should construct `LLM.request(...)`, `LLM.model(...)`, `Message.*`, `SystemPart`, `ToolCallPart`, `ToolResultPart`, or `ToolDefinition` values from `@opencode-ai/llm`.
- `ai-sdk.ts` and `native-runtime.ts` both emit `@opencode-ai/llm` `LLMEvent`s so downstream session processing does not care which runtime handled the request.

Keep new integration code on one of these seams. Avoid importing session services into `native-request.ts`; pass normalized data through `RequestInput` instead.

## Runtime selection

Both runtimes converge on the same `LLMEvent` stream consumed by the session processor. The gate is per-request: a single session can route some calls through native and fall back for others.

```txt
                             ╭───────────────────╮
╭───────────────────────────▶│ session processor │
│                            ╰─────────┬─────────╯
│                                      │
│                                      │
│                                      │
│                                      ▼
│                         ╭─────────────────────────╮
│                         │ LLM.Service (../llm.ts) │
│                         ╰────────────┬────────────╯
│                                      │
│                                      │
│                                      │
│                                      ▼
│                                ╭───────────╮
│                              ╭─╯           ╰─╮
│                              │  native gate  │
│                              ╰─╮           ╭─╯
│                                ╰─────┬─────╯
│                                      │
│                     ╭────── no ──────┴─────── yes ────────╮
│                     │                                     │
│                     ▼                                     ▼
│       ╭───────────────────────────╮             ╭───────────────────╮
│       │          AI SDK           │             │ native-runtime.ts │
│       │ streamText / generateText │             ╰────────┬──────────╯
│       ╰─────────────┬─────────────╯                      │
│                     │                                    │
│                 ╭───╯                                    │
│                 │                                        │
│                 ▼                                        ▼
│     ╭───────────────────────╮             ╭────────────────────────────╮
│     │       ai-sdk.ts       │             │     native-request.ts      │
│     │ fullStream → LLMEvent │             │ session input → LLMRequest │
│     ╰──────────┬────────────╯             ╰──────────────┬─────────────╯
│                │                                         │
│                │                                     ╭───╯
│                │                                     │
│                ▼                                     ▼
│       ╭─────────────────╮             ╭─────────────────────────────╮
╰───────┤ LLMEvent stream │◀────────────┤ LLMClient · RequestExecutor │
        ╰─────────────────╯             ╰─────────────────────────────╯
```

`native-runtime.ts` evaluates the gate and either bridges into `@opencode-ai/llm` or returns control so `llm.ts` can take the AI SDK path. Tool execution stays opencode-owned in both branches; only request lowering and transport differ.

Safety boundary:

- AI SDK remains the default.
- PromptLab is the deliberate exception: its native reasoning/action boundary is mandatory so it cannot silently regress to the legacy compatibility adapter.
- PromptLab primary and advisory turns sharing a HeelCode Session must use distinct inference scopes. Primary uses `${sessionID}:primary`; every advisory turn uses a fresh `${sessionID}:advisory:${uuid}` so title/small-model work cannot take the primary continuation lock.
- PromptLab tool execution is HeelCode-owned and limited to `glob`, `grep`, `read`, `edit`, `write`, and `bash`. Keep schema validation, permissions, abort propagation, and settlement on this side of the boundary.
- PromptLab action parsing accepts one exact typed `heelcode.tool` object, the explicit `HEELCODE_ACTION` envelope, or one unambiguous trailing typed object after a progress prefix. Reject multiple candidates and never infer a tool from ordinary prose. Progress-only stops and rejected malformed/multiple actions return to the Session loop as bounded explicit action-conformance turns; clear only the protocol error, preserve durable history, and reset the three-attempt allowance after a real tool call.
- A lost daemon continuation may be reconstructed from durable Session messages, with tool results labeled untrusted. Do not treat that fallback as compaction or signature-preserving reasoning replay.
- Preserve PromptLab authentication failures as authentication failures. The daemon startup must check uncached authenticated state before cached models, and the native runtime must not discard the connector's safe error body or turn an upstream 401 into a generic inference 500.
- PromptLab's loopback SSE request has no Bun idle timeout, but the connector enforces a separate meaningful-event silence deadline and aborts the upstream job. Heartbeat bytes are not model progress. The processor retries transient PromptLab silence/loopback failures at most three times with backoff; do not create an endless reconnect loop in this runtime.
- The daemon health response carries the native protocol version. Startup replaces an incompatible detached daemon instead of accepting a stale `/health` response. A default-loopback connection failure may call `ensurePromptLabReady([])` and retry exactly once so an already-open TUI can recover if its daemon exits; never restart for a custom base URL or an aborted request.
- `OPENCODE_EXPERIMENTAL_NATIVE_LLM=true` or the umbrella `OPENCODE_EXPERIMENTAL=true` opts in. Native is not a global replacement.
- Native execution currently supports OpenAI, opencode-managed OpenAI-compatible, and Anthropic API-key paths backed by `@ai-sdk/openai`, `@ai-sdk/openai-compatible`, or `@ai-sdk/anthropic` catalog entries.
- Unsupported providers, OpenAI OAuth, and missing API-key cases fall back to AI SDK.
