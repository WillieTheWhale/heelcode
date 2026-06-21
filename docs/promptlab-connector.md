# PromptLab Connector

## Purpose

`heelcode-promptlabd` is the local bridge between heelcode and UNC PromptLab. It exposes a local OpenAI-compatible API so the existing opencode provider path can keep handling sessions, tools, streaming, and model selection while PromptLab remains the remote model backend.

## API Shape

The daemon exposes:

- `GET /health`
- `GET /v1/models`
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

The observed PromptLab frontend starts chats by posting to `/api/agents/chat/:endpoint`. heelcode intentionally strips each OpenAI-compatible request down to the smallest PromptLab-compatible chat-start payload: `text`, `messageId`, `parentMessageId`, `conversationId`, `isCreatedByUser`, `endpointOption`, `endpoint`, `model`, `addedConvo`, `isTemporary`, `isRegenerate`, `isContinued`, `ephemeralAgent`, and `manualSkills`, plus supported sampling fields when present.

The daemon does not send OpenAI `messages`, `tools`, `tool_choice`, `prompt`, or `userMessage` fields to PromptLab. Those remain local compatibility state for opencode. Chat start requests must include same-origin browser headers (`Origin`, `Referer`, and fetch metadata) matching the PromptLab web app; otherwise PromptLab returns an `Illegal request` SSE error.

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

The client retries a failed authenticated request once after `POST /api/auth/refresh` returns a replacement token. PromptLab bearer JWTs are short lived; when a daemon request reports expiration, running `heelcode` refreshes the stored session from the logged-in normal Chrome profile before opening the TUI. Manual `capture --store-session` remains available for debugging.

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

The helper does not create a blank Chrome profile, does not close Chrome tabs, and does not drive the ONYEN form. Chrome owns the login, saved passwords, MFA, and cookies. The helper only reads PromptLab cookies from the local Chrome profile and stores the resulting PromptLab session in Keychain when `--store-session` is used.

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

## Local Tool Calls

PromptLab's web app has server-side agent tool events (`on_run_step`, `on_run_step_delta`, and `tool_calls` step details), but those are not the same as opencode local workspace tools. heelcode keeps local tool execution on the opencode side. PromptLab receives a text prompt that includes the user's request, prior opencode-visible conversation state, and a compact synthetic tool-call instruction when tools are available.

The daemon uses these compatibility paths:

- pass through OpenAI-compatible `tool_calls` when PromptLab or a compatible backend returns them;
- translate the synthetic `<heelcode_tool_call>{...}</heelcode_tool_call>` protocol into OpenAI-compatible streaming tool calls;
- infer safe inspection tool calls from PromptLab prose when a model describes the tool it should use instead of emitting the XML tag;
- preflight explicit requests such as "use the glob tool with pattern \*" into a local tool call before contacting PromptLab;
- preflight explicit sequenced requests, such as grep-then-read, when prior tool output contains enough concrete path information.

This makes explicit local tool requests, follow-up inspection loops, and subagent handoff through the local `task` tool execute end to end through opencode. PromptLab is the remote text-generation backend; opencode remains the harness that executes tools, receives tool results, compacts context, and continues the loop.

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
- OpenAI-compatible and synthetic local tool-call conversion;
- explicit local tool preflight behavior;
- text, header, and JSON redaction.

Run them with:

```bash
bun run --cwd packages/promptlab test
```

## UNC Use Constraints

PromptLab access is for eligible UNC affiliates. Do not use heelcode to publish PromptLab-backed AI services for the general public. Follow UNC PromptLab terms, privacy guidance, and data classification rules. Avoid sending regulated or sensitive data that PromptLab does not permit.

## Known Limitations

- The observed chat payload shape is intentionally stripped to avoid PromptLab-specific prompt wrappers where possible. A sanitized HAR would still be useful for attachments and PromptLab-native assistant workflows.
- If stale opencode state references direct `openAI` or `anthropic` model IDs, heelcode aliases those selections to matching configured PromptLab models when possible.
- Native PromptLab tools are server-side PromptLab agent tools, not arbitrary opencode local tools.
- Local tool execution is strongest for explicit tool requests and deterministic inspection sequences. General autonomous tool choice still depends on PromptLab following the synthetic tool-call instruction or producing prose that the daemon can safely infer.
- ONYEN browser login stays in the user's normal Chrome profile, so Microsoft MFA/security-info prompts remain user-side browser steps.
