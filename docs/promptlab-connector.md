# PromptLab Connector

## Purpose

`heelcode-promptlabd` is the local bridge between HeelCode and UNC PromptLab. Its native inference endpoint preserves provider reasoning and delegates tool ownership to the HeelCode harness. A legacy OpenAI-compatible API remains for compatibility but is not used by PromptLab-backed harness turns.

## API Shape

The daemon exposes:

- `GET /health`
- `GET /v1/models`
- `POST /v1/native/inference`
- `POST /v1/chat/completions`
- `POST /v1/chat/abort`
- `GET /promptlab/active`
- `GET /promptlab/status/:conversationID`
- `GET /promptlab/config`

The PromptLab client uses the observed PromptLab routes:

- `GET /api/config`
- `GET /api/models`
- `GET /api/endpoints`
- `POST /api/auth/refresh`
- `POST /api/agents/chat/:endpoint`
- `GET /api/agents/chat/stream/:streamID`
- `GET /api/agents/chat/status/:conversationID`
- `GET /api/agents/chat/active`
- `POST /api/agents/chat/abort`

The observed PromptLab frontend starts chats by posting to `/api/agents/chat/:endpoint`. The route is also the verified path for provider-exposed reasoning, but it is still a LibreChat web-chat controller rather than an unmediated provider API. It normalizes the request, persists the conversation, calls the configured provider, and wraps provider output in PromptLab SSE events.

Source tracing against LibreChat v0.8.7 (`9e74cc0e57b395926122bd4062c1fcedc48ed465`) established a sharper boundary. `/api/agents/chat/:endpoint` always enters `AgentController`; `api/server/services/Endpoints/agents/build.js` selects the ephemeral Agent ID, and `packages/api/src/agents/load.ts` synthesizes an Agent from the endpoint, model parameters, `promptPrefix`, and server-approved tool toggles. Thus `ephemeralAgent: false` does not bypass LibreChat's internal Agent implementation. It prevents caller-requested ephemeral capabilities. HeelCode does not create a saved PromptLab Agent and still owns the outer loop.

The existing OpenAI-compatible daemon strips each request down to a PromptLab chat-start payload containing `text`, `messageId`, `parentMessageId`, `conversationId`, `isCreatedByUser`, `endpointOption`, `endpoint`, `model`, `addedConvo`, `isTemporary`, `isRegenerate`, `isContinued`, `ephemeralAgent`, `manualSkills`, `promptPrefix`, and selected sampling fields. That daemon path remains a legacy compatibility adapter; it is not the target architecture for native Heelcode reasoning and tool loops.

The daemon does not send OpenAI `messages`, `tools`, `tool_choice`, `prompt`, or `userMessage` fields to PromptLab. Live canary requests verified that the web-chat controller ignores caller-provided `messages`, `instructions`, `additional_instructions`, `tools`, and `tool_choice`; only the user `text` and `promptPrefix` canaries reached the model. Chat start requests must include same-origin browser headers (`Origin`, `Referer`, and fetch metadata) matching the PromptLab web app; otherwise PromptLab returns an `Illegal request` SSE error.

## Provider Reasoning Stream

Live probes on 2026-07-15 verified structured reasoning for:

| PromptLab endpoint | Verified model | Required controls | Observed result |
| --- | --- | --- | --- |
| `azureOpenAI` | `gpt-5.4-mini` | `useResponsesApi: true`, `reasoning_effort`, `reasoning_summary`, `verbosity` | `on_reasoning_delta` events and final `think` plus `text` parts |
| `bedrock` | `us.anthropic.claude-sonnet-4-6` | `thinking: true`, `thinkingBudget`, `effort` | `on_reasoning_delta` events and final `think` plus `text` parts |
| `google` | `gemini-3.1-pro-preview` | `thinking: true`, `thinkingLevel` | `on_reasoning_delta` events and final `think` plus `text` parts |

The chat-start response is a small JSON object containing a `streamId`. `GET /api/agents/chat/stream/:streamID` then returns SSE. The outer SSE event may simply be `message`; the JSON payload carries the meaningful event in its own `event` field. Relevant payloads include:

```text
on_context_usage
on_run_step
on_reasoning_delta  data: { id, delta }
on_message_delta    data: { id, delta: { content: [...] } }
on_token_usage
final               responseMessage.content: [{ type: "think", think }, { type: "text", text }]
```

Reasoning must stay distinct from final text. The existing `transformPromptLabSSEToOpenAI` adapter does not consume `on_reasoning_delta`, so using it for the future native integration silently loses reasoning. The native integration should translate PromptLab events directly into Heelcode's canonical reasoning, text, usage, and completion events.

The exposed reasoning is provider-returned reasoning content or summary, not private model state. PromptLab remains one abstraction layer above the provider: LibreChat owns request normalization and conversation persistence. The observed final `think` parts did not carry Bedrock thinking signatures or Gemini thought signatures. For now, reliable multi-turn continuation reuses the same PromptLab `conversationId` and supplies the previous assistant `responseMessage.messageId` as the next `parentMessageId`; a live two-turn probe preserved the first turn this way.

Reasoning controls request provider thinking but do not guarantee that every trivial turn emits a reasoning part. Regression probes should use a problem that actually requires multiple reasoning steps and should validate the stream structure without depending on an exact delta count.

## Native HeelCode Transport

The implemented path is:

```text
HeelCode Session loop
  -> opencode native runtime
  -> POST http://127.0.0.1:43117/v1/native/inference
  -> one POST /api/agents/chat/:endpoint
  -> one GET /api/agents/chat/stream/:streamID
  -> canonical HeelCode events
  -> HeelCode permission and tool runtime
  -> next Session-loop turn
```

PromptLab models select this path regardless of the experimental native-LLM flag. If it is unavailable, HeelCode fails closed instead of falling back to the AI SDK/OpenAI compatibility endpoint. The daemon keys continuation and active-turn ownership by inference scope: `${sessionID}:primary` for the harness turn and a fresh `${sessionID}:advisory:${uuid}` for each title/small-model turn. This prevents advisory inference from producing the original same-Session `409 Conflict`. Advisory requests are transient, so their one-use provider identity is not retained after the turn. Primary provider conversation identity remains process-local. When that continuation is lost across a daemon/CLI restart, the connector serializes durable HeelCode Session history into the next provider request and labels tool results as untrusted data. This is restart reconstruction, not compaction; compaction remains deferred.

| PromptLab event/content | HeelCode canonical events |
| --- | --- |
| first transport activity | `step-start` |
| `on_reasoning_delta` | `reasoning-start`, `reasoning-delta`, `reasoning-end` |
| final `think` without streamed deltas | reasoning start/delta/end from the provider-returned part |
| final ordinary `text` | `text-start`, `text-delta`, `text-end` |
| one validated typed structured action | `tool-input-start/delta/end`, then `tool-call` |
| usage event or final metadata | normalized usage plus raw PromptLab usage metadata |
| final completion | `step-finish`, `finish` |
| PromptLab error or server tool event | `provider-error` |

Final visible text is buffered until its complete value is known. The parser accepts a bare typed action, an explicit `HEELCODE_ACTION` envelope, or one unambiguous trailing object beginning exactly with `{"type":"heelcode.tool"` after a progress prefix. It rejects multiple candidates, malformed suffixes, extra keys, and ordinary prose without the explicit type. Reasoning still streams separately. Cancellation propagates to the PromptLab stream and abort route, clears the active inference scope, and permits a clean durable continuation.

Bun's loopback server otherwise resets a quiet request after its default ten-second idle window, which reproduced the user-visible `Connection reset by server` retry loop before the first reasoning event. `/v1/native/inference` disables that transport idle timeout. A separate meaningful-progress watchdog remains enabled through `HEELCODE_PROMPTLAB_SILENCE_TIMEOUT_MS` (120,000 ms by default): reasoning deltas, streamed message content, and final responses reset it, while SSE heartbeats and unknown status traffic do not. Expiry cancels the stream, calls PromptLab's abort route, frees the inference scope, and emits `PromptLab inference produced no events for …ms`. The Session processor treats that error, loopback 500s, and connection failures as transient PromptLab transport failures with normal backoff and at most three retries. This is bounded recovery, not an unbounded reconnect loop.

`GET /health` includes the daemon's native protocol version and PID. HeelCode startup accepts only the matching protocol, asks a newer compatible daemon to shut down through `POST /shutdown`, and on macOS may terminate a verified stale `heelcode-promptlabd` port owner that predates the shutdown endpoint. This prevents a detached daemon loaded from older source from passing a shallow health check indefinitely. The native runtime also handles one default-loopback connection failure by running daemon readiness and retrying the request once. It does not retry custom base URLs, aborts, or a second failure.

## Caller-Owned Tool Boundary

Caller-owned native function calling is unavailable through the authenticated student surface tested on 2026-07-15:

- `parseCompactConvo` selects endpoint-specific compact schemas. Azure OpenAI excludes `tools`, `functions`, choices, and `response_format`. Google excludes `functionDeclarations` and `toolConfig`.
- Bedrock strips top-level Converse `toolConfig`. Nesting it inside `additionalModelRequestFields` does not bind a Converse tool; the live request produced a provider error and no native call.
- Remote `/api/agents/v1/responses` and `/api/agents/v1` require remote-Agent authentication and permission. Their controllers also load a saved Agent and build tools from that Agent; request `tools` and `tool_choice` are not bound into the run.
- Presets and model specs use the same compact parsers or administrator-owned configuration, not a caller-owned per-turn schema channel.

Sanitized live matrix:

| Provider | Injected boundary | Persisted | Result |
| --- | --- | --- | --- |
| GPT / Azure OpenAI | tools/functions/choices, `response_format`, Responses input/instructions | none | canaries absent; no native call |
| Sonnet / Bedrock | top-level `tools` and `toolConfig` | none | canaries absent; no native call |
| Sonnet / Bedrock | nested `additionalModelRequestFields.toolConfig` | no conversation tool state | provider error; no native call |
| Gemini / Google | declarations/config and generation structured-output fields | none | canaries absent; no native call |

The fallback is therefore named **HeelCode structured action selection**, never native tool calling. The preferred selection is a bare object:

```json
{"type":"heelcode.tool","name":"read","arguments":{"filePath":"AGENTS.md"}}
```

Models that cannot reliably emit bare JSON may use an explicit two-line envelope with `HEELCODE_ACTION` followed by the same object. Live GPT runs also showed a short progress sentence followed by an otherwise exact typed object. HeelCode therefore recognizes one such trailing typed object without guessing from the sentence. It rejects multiple objects, malformed JSON, extra keys, unknown names, and schema-invalid arguments locally. It creates the call ID, applies normal permissions, executes the tool, and returns a JSON-encoded tool result explicitly marked as untrusted data. PromptLab tools remain empty; any server `tool_call`, `tool_calls`, or `tool_use` event is rejected. The verified harness allowlist is `glob`, `grep`, `read`, `edit`, `write`, and `bash`.

The task workspace is the directory from which HeelCode was invoked, not an enclosing Git worktree. This distinction matters for collections of sibling projects stored in one parent repository: an empty `tmp/example` invocation must create the requested project in `tmp/example`, not search the parent and select an unrelated sibling. PromptLab tool execution therefore treats any parent or sibling path as `external_directory` and appends a local deny rule after ordinary or automatic permission rules. The model receives the denial as a tool result and may recover with an action inside the task workspace; `--dangerously-skip-permissions` does not override this boundary.

## Real Harness Benchmark

The acceptance benchmark used only the checkout-local HeelCode binary against a clean clone of `sindresorhus/p-map`; it did not invoke or modify an installed OpenCode. The task added `AbortSignal` support to `pMapIterable` across runtime code, types, documentation, and tests.

The initial Gemini run completed in 266.4 seconds across 38 assistant/provider attempts. It streamed 9 canonical reasoning parts containing 9,081 characters and settled 35 HeelCode-owned tools: 16 `bash`, 10 `edit`, 5 `read`, 2 `grep`, 1 `glob`, and 1 `write`. Two initial `edit` actions failed exact-match validation and were recovered through later reads/edits. The run installed dependencies, recovered from lint failures, diagnosed a behavioral abort-test failure, removed a temporary probe, and finished with the then-current 53-test suite passing.

Independent audit found that a throwing `iterator.return()` could still mask the abort reason. A second real Gemini HeelCode Session corrected that edge case in 130.8 seconds across 14 assistant/provider attempts, with 10 canonical reasoning parts (6,051 characters) and 13 completed tools (10 `bash`, 3 `edit`). It created and ran a focused reproduction, hit 10 lint errors, removed the temporary file, fixed the test formatting, and finished with 54 tests passing. An independent post-run `npm test`, `git diff --check`, and hidden abort probe all passed.

A stricter cold-start acceptance then used only the checkout-local HeelCode binary against a fresh clone of `sindresorhus/p-limit`; the installed OpenCode was neither invoked nor modified. GPT 5.4 Mini implemented repeatable `limit.onEmpty()` and `limit.onIdle()` behavior across runtime code, public types, type assertions, documentation, and AVA tests. The primary Session lasted 345.7 seconds, contained 23 assistant/provider messages, emitted 14 canonical reasoning parts, and completed 25 HeelCode-owned actions: 13 `bash`, 8 `read`, and 4 recorded patches. It encountered `xo not found`, installed the clone's dependencies, then encountered and repaired 18 lint errors before completing with 22 tests passing and a final answer.

An audit Session added three missing lifecycle cases—`onEmpty()` while work is active with no pending queue, `onIdle()` after rejection, and reuse after returning to idle. It lasted 299.1 seconds, contained 14 assistant/provider messages, emitted 5 canonical reasoning parts, and completed 14 HeelCode-owned actions: 7 `bash`, 4 `read`, and 3 patches. Its first test formulation failed XO on promise callbacks; HeelCode read the failing lines, rewrote them, reran the suite, reviewed the diff, and returned a final answer with 25 tests passing. Independent validation then repeated `npm test` and `git diff --check` and ran a separate Node probe covering multiple simultaneous waiters, rejection, `clearQueue()` while active, and later reuse. All passed, with changes limited to the five intended files.

This p-limit run proves that native PromptLab inference can sustain a real HeelCode-owned inspect/edit/test/recovery loop rather than merely serving a deeper chat request. It also exposed an important reliability boundary: GPT Mini sometimes stopped after progress prose, used an invalid action form, or entered repeated provider retries when asked for a large combined patch. Explicitly requesting one exact typed action and decomposing the work into smaller edits recovered the run. That is valid evidence for transport, continuation, local tool ownership, and test recovery, but it is not yet evidence of fully unattended model-independent operation.

A second isolated p-limit acceptance on 2026-07-15/16 exercised the fixed transport in the actual interactive checkout-local TUI. GPT 5.4 Mini added delegated `activeCount`, `pendingCount`, `clearQueue()`, and mutable `concurrency` controls to `limitFunction` across runtime code, declarations, AVA tests, `tsd` assertions, and documentation. The durable Session contained 19 assistant/provider attempts, 13 reasoning parts totaling 10,624 characters, and 16 completed HeelCode-owned actions (8 `bash`, 7 `read`, 1 `glob`). It read six repository files over multiple turns, edited five files, encountered missing dependencies, installed them, recovered from an XO method-style failure, ran the full test command repeatedly, and returned a final answer after 22 tests passed. An independent `git diff --check` and `npm test` also passed. Two genuinely silent GPT turns were bounded and aborted instead of hanging; completing the run required two explicit steering prompts, so this proves the real harness loop while preserving the unattended-reliability caveat above. The same live TUI path reached Gemini 3.5 Flash native inference, which PromptLab rejected with a real `token_balance` response (`balance: 0`); no Gemini benchmark claim is made for that unavailable allocation.

A final visible-session regression launched `/Users/williamkeffer/.local/bin/heelcode`—a symlink to this checkout's `packages/opencode/bin/opencode`—inside a real PTY rooted at a fresh `/tmp` p-limit clone and streamed that TUI into a visible local browser tab. The first GPT Mini prompt emitted reasoning, executed HeelCode's local `read` on `package.json`, consumed the result, and answered in about ten seconds. The test then reproduced stale-daemon behavior, added the protocol handshake, restarted the TUI on the new runtime, completed a first read loop, deliberately terminated protocol-2 daemon PID ownership, and submitted a second prompt without reopening HeelCode. The native runtime started a new daemon, reconstructed continuation from the durable Session, executed `read` on `index.js`, and returned the wrapper expression. This directly covers the user's visible launch path rather than relying only on an app-internal PTY.

The unattended acceptance on 2026-07-15/16 used the same visible PTY bridge and checkout-local `/Users/williamkeffer/.local/bin/heelcode` against a fresh `create-next-app` project under `/tmp`. One user prompt asked GPT 5.4 Mini to build a responsive Three.js `JOHN CHRISTOPHER` block-letter landing page with pointer motion, reduced-motion support, lint, and a production build. No steering or follow-up message was sent. The first assistant response contained only progress narration; the Session loop recorded one bounded `promptlab action conformance retry` and continued the original task automatically. The durable Session ran for 464.4 seconds, contained 18 assistant/provider messages and 15 reasoning parts totaling 29,020 characters, and completed 16 HeelCode-owned actions: 6 `read`, 4 `glob`, and 6 `bash`. It installed Three.js, applied a 466-line change across five files, ran a production build, diagnosed missing Three.js declarations, installed `@types/three`, then ran lint and the production build successfully before returning a concrete final answer. A separate post-run `npm run lint && npm run build` also passed. This is the first acceptance in this investigation that proves a substantive one-prompt, no-steering feature loop in the actual interactive HeelCode launch path.

Provider behavior is not uniform:

- Gemini completed the full edit/test/recovery loop and the audit correction.
- Sonnet produced canonical reasoning and a real `grep`/continuation loop on a bounded task, but the longer feature run encountered repeated provider failures and did not complete.
- GPT produced canonical reasoning and real local inspection tools, but intermittently emitted malformed JSON, multiple actions, or progress-only stops. The constrained trailing typed-object parser handles one typed action after a prefix; the Session loop now recovers progress-only stops and rejected malformed/multiple actions with a private, bounded, explicit action-conformance turn rather than inferring or executing prose.

These results establish a functioning unattended GPT harness path, not universal model reliability. PromptLab bearer credentials are short lived, provider/quota availability still varies, and compaction remains deferred. Transport and action-conformance retries are bounded and visible; exhausting them remains a terminal error rather than silently changing inference paths.

## Model Discovery

The daemon fetches `/api/models` and `/api/endpoints`, normalizes the result, and exposes stable OpenAI-compatible model IDs:

```text
promptlab/<endpoint>/<model>
```

Examples:

```text
promptlab/azureOpenAI/gpt-4.1
promptlab/google/gemini-2.5-flash
promptlab/bedrock/us.anthropic.claude-sonnet-4-6
```

PromptLab may also return raw provider-key model groups such as `openAI` and `anthropic`. Those groups require user-provided upstream provider API keys in the PromptLab UI and are not the university-backed endpoints heelcode should select by default. The connector filters them out when `/api/endpoints` reports configured PromptLab endpoints. Claude models offered by PromptLab are selected through the configured `bedrock` endpoint.

The heelcode provider discovers this catalog from:

```text
http://127.0.0.1:43117/v1/models
```

The normal CLI path starts this daemon automatically:

```bash
heelcode
```

Override with:

```bash
export HEELCODE_PROMPTLAB_URL=http://127.0.0.1:43117/v1
```

## Authentication

Runtime auth inputs:

- `PROMPTLAB_BEARER_TOKEN`
- `PROMPTLAB_COOKIE`
- `PROMPTLAB_BASE_URL`

Persistent local auth inputs:

- `heelcode-promptlabd credentials set --username <onyen>`
- `heelcode-promptlabd credentials status`
- `heelcode-promptlabd credentials delete`
- `heelcode-promptlabd session set --bearer-token-stdin`
- `heelcode-promptlabd session set --cookie-stdin`
- `heelcode-promptlabd session delete`

Persistent credentials and session material are stored in macOS Keychain under `heelcode-promptlabd`.

The client retries a failed authenticated request once after `POST /api/auth/refresh` returns a replacement token and persists that replacement for later daemon requests. PromptLab also rotates its refresh cookie through `Set-Cookie`; both ordinary refresh and Chrome capture merge that replacement into the stored cookie header before the old cookie becomes unusable. Discarding the rotated cookie recreates the delayed `jwt expired` failure even though the first bearer exchange succeeds. If both the bearer and stored refresh cookie have expired, the daemon opens PromptLab in the normal Chrome profile and waits for an authenticated browser session before retrying once. HeelCode startup validates `/promptlab/active` before consulting `/v1/models`; the model catalog is cached and must never be used as proof that authentication is alive.

Interactive HeelCode startup opens PromptLab in normal Chrome before the readiness check, even when the stored daemon session is still valid, so the browser login surface remains visible and available. Set `HEELCODE_PROMPTLAB_OPEN_BROWSER=0` to suppress that startup tab for interactive automation. If Chrome is logged out, startup waits for the user-owned ONYEN/MFA flow and exits before opening the TUI or creating a model Session if login is not completed during that window. An authentication failure during inference reopens the same browser surface, remains HTTP 401 if recovery fails, and reports an actionable authentication-expired message instead of the misleading `500 Internal Server Error` that previously hid `jwt expired`. Manual `capture --store-session` remains available for debugging.

To investigate the live authenticated API without committing secrets, run:

```bash
bun run --cwd packages/promptlab capture
```

The script opens PromptLab in the normal Google Chrome profile and polls Chrome's real profile cookie store. After the user is logged in to PromptLab in Chrome, it refreshes the PromptLab bearer token from the real Chrome cookies and writes only redacted status/model data to `/tmp/heelcode-promptlab-capture.json`.

To save a successful browser session into Keychain for daemon use:

```bash
bun run --cwd packages/promptlab capture --store-session
```

By default it reads Chrome profiles from:

```text
~/Library/Application Support/Google/Chrome
```

Override with:

```bash
export HEELCODE_CHROME_USER_DATA_DIR="$HOME/Library/Application Support/Google/Chrome"
```

The helper does not create a blank Chrome profile, does not close Chrome tabs, and does not drive the ONYEN form. Chrome owns the login, saved passwords, MFA, and cookies. The helper only opens PromptLab, reads PromptLab cookies from the local Chrome profile, and stores the resulting PromptLab session in Keychain when `--store-session` is used. Daemon recovery reuses the same cookie-reading implementation and opens the login surface, but it does not control the login form.

Override the cookie polling interval with:

```bash
export HEELCODE_PROMPTLAB_CAPTURE_POLL_MS=250
```

Do not commit:

- ONYEN passwords;
- cookies;
- bearer tokens;
- raw HAR files;
- PromptLab chat content;
- exported model payloads that include names, emails, or identifiers.

## ONYEN Automation

Preferred login path:

1. Open PromptLab in the user's normal Google Chrome profile.
2. Let Chrome handle saved passwords, ONYEN/OpenID login, and MFA.
3. Read the PromptLab refresh cookie from Chrome's local cookie store.
4. Exchange it for a short-lived PromptLab bearer token and store only the PromptLab session material in macOS Keychain.

Password automation should remain explicit opt-in and local-only. The default path should not automate ONYEN credentials at all; it should rely on the user's real Chrome profile and saved browser session.

The 2026-07-15 cold-start regression reproduced the original user-visible failure as a cached model catalog followed by loopback 500 whose hidden body contained PromptLab 401 `jwt expired`. Live acceptance after the fix covered: logged-out startup stopping before model execution; browser-session capture with rotated-cookie persistence; a deliberately invalid in-memory bearer refreshing and persisting a second rotated session; and a fresh client authenticating from that persisted Keychain state.

The final no-listener acceptance launched the checkout-local HeelCode binary against the `p-map` test clone with Gemini 3.1 Flash Lite. HeelCode started its own daemon from persisted rotated auth, completed three provider turns, streamed two reasoning parts totaling 623 characters, executed one local `bash` and one local `read`, consumed both tool results, and returned a correct final answer with exit code 0. A separate Gemini 3.1 Pro audit completed four local actions including the full 54-test suite after daemon/Session restart reconstruction, then stopped on PromptLab token balance before its final turn; that quota error is distinct from connector authentication and transport health.

## Legacy Local Tool Compatibility

PromptLab's web app has server-side agent tool events (`on_run_step`, `on_run_step_delta`, and `tool_calls` step details), but those are not the same as opencode local workspace tools. The legacy OpenAI-compatible daemon route attempts to keep local tool execution on the opencode side by placing a synthetic tool-call instruction in `promptPrefix`.

The daemon uses these compatibility paths:

- pass through OpenAI-compatible `tool_calls` when PromptLab or a compatible backend returns them;
- translate the synthetic `<heelcode_tool_call>{...}</heelcode_tool_call>` protocol into OpenAI-compatible streaming tool calls;
- infer safe inspection tool calls from PromptLab prose when a model describes the tool it should use instead of emitting the XML tag;
- preflight explicit requests such as "use the glob tool with pattern \*" into a local tool call before contacting PromptLab;
- preflight explicit sequenced requests, such as grep-then-read, when prior tool output contains enough concrete path information.

This path exists only for compatibility with old clients. PromptLab-backed HeelCode model turns do not use it. New work must not extend XML tool calls, prose inference, or deterministic preflight.

The native harness endpoint supplies one provider inference step—including structured reasoning—while HeelCode owns action validation, permissions, execution, results, loops, goals, and subagents. Its typed structured-action boundary is described above.

## Testing Metrics

Track these practical metrics during development:

- endpoint coverage across config, models, endpoints, chat start, stream, refresh, status, active, and abort;
- model discovery accuracy compared with the PromptLab UI;
- successful stream completion rate;
- auth refresh success rate;
- OpenAI-compatible response compatibility for streaming and non-streaming calls;
- secret-redaction coverage for headers, cookies, bearer tokens, ONYEN credentials, names, emails, and prompt text;
- absence of secrets in logs, test fixtures, and committed files.

Current focused tests cover:

- model catalog normalization;
- encoded model ID round trips;
- OpenAI message-to-PromptLab payload adaptation;
- PromptLab stream delta extraction;
- native reasoning, text, action, usage, and completion mapping;
- strict structured-action parsing and malformed-output rejection;
- local tool-input schema validation before execution;
- PromptLab runtime selection and HeelCode-owned tool settlement;
- OpenAI-compatible and synthetic local tool-call conversion;
- explicit local tool preflight behavior;
- text, header, and JSON redaction.

Run the relevant regressions with:

```bash
cd packages/promptlab
bun test --timeout 30000
bun typecheck

cd ../opencode
bun test test/session/llm-native.test.ts --timeout 30000 --only-failures
bun typecheck
```

Live validation on 2026-07-15 also demonstrated nonempty canonical reasoning for GPT, Sonnet, and Gemini without PromptLab tool calls; a connector-level sequence of five permitted `read` actions followed by a final GPT answer; ordinary two-turn canary recall; actual HeelCode Session cancellation followed by a clean request on the same Session; and the full repository benchmark documented above.

## UNC Use Constraints

PromptLab access is for eligible UNC affiliates. Do not use heelcode to publish PromptLab-backed AI services for the general public. Follow UNC PromptLab terms, privacy guidance, and data classification rules. Avoid sending regulated or sensitive data that PromptLab does not permit.

## Known Limitations

- The observed chat payload shape is intentionally stripped to avoid PromptLab-specific prompt wrappers where possible. A sanitized HAR would still be useful for attachments and PromptLab-native assistant workflows.
- If stale opencode state references direct `openAI` or `anthropic` model IDs, heelcode aliases those selections to matching configured PromptLab models when possible.
- Native PromptLab tools are server-side PromptLab agent tools, not arbitrary opencode local tools, and must not be used as a substitute for Heelcode orchestration.
- The OpenAI compatibility adapter discards PromptLab reasoning deltas and relies on synthetic XML/prose tool behavior. It is legacy-only; PromptLab harness turns fail rather than fall back to it.
- Caller-owned OpenAI `messages` and native tool schemas do not pass through the PromptLab web-chat controller. PromptLab conversation IDs are therefore still required for reliable history continuation.
- The working route uses LibreChat's internal synthetic ephemeral Agent wrapper. No route that both bypasses that wrapper and accepts the authenticated student session was found.
- Exact PromptLab conversation/message continuation remains process-local. Daemon restart now reconstructs the next turn from durable HeelCode Session messages, but it does not restore provider reasoning signatures or the original PromptLab continuation graph. Compaction is not implemented yet.
- Model action adherence and provider stability vary. Gemini completed the repository benchmark; longer Sonnet turns encountered provider failures; GPT still sometimes emits malformed/multiple actions or progress-only stops. HeelCode now uses bounded explicit action-conformance turns for those GPT failures and bounded processor retries for transient transport failures. Exhaustion remains visible and terminal rather than falling back to the legacy adapter.
- The visible one-prompt Next.js acceptance proves unattended recovery from progress-only output and from a real build failure. It does not prove unlimited-duration reliability, compaction, or equal behavior across every PromptLab model. Keep acceptance tests isolated, record all retries and manual steering, and require a substantive implementation plus real verification before describing another model/path as autonomous.
- GPT Responses context metadata reported zero tool-schema tokens and no tool-call events in no-tools probes, but also reported one tool in internal bookkeeping, an empty tool-token map, and 4,224 cached input tokens. Explicit `useWebSearch: false` was not persisted and did not change those values. Treat the route as tool-inert for the tested turns, not as proof that LibreChat has no internal framing or tool machinery.
- ONYEN browser login stays in the user's normal Chrome profile, so Microsoft MFA/security-info prompts remain user-side browser steps.
