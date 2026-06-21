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

The authenticated chat payload is intentionally conservative until a sanitized HAR confirms the exact production schema.

## Model Discovery

The daemon fetches `/api/models` and `/api/endpoints`, normalizes the result, and exposes stable OpenAI-compatible model IDs:

```text
promptlab/<endpoint>/<model>
```

Examples:

```text
promptlab/openAI/gpt-4.1
promptlab/anthropic/claude-sonnet-4-5
```

The heelcode provider discovers this catalog from:

```text
http://127.0.0.1:43117/v1/models
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

The client retries a failed authenticated request once after `POST /api/auth/refresh` returns a replacement token.

To investigate the live authenticated API without committing secrets, run:

```bash
PROMPTLAB_ONYEN_USERNAME=<onyen-or-onyen@ad.unc.edu> bun run --cwd packages/promptlab capture
```

The script reads the password from stdin with terminal echo disabled, opens Chrome, and writes only redacted status/model data to `/tmp/heelcode-promptlab-capture.json`.

Do not commit:

- ONYEN passwords;
- cookies;
- bearer tokens;
- raw HAR files;
- PromptLab chat content;
- exported model payloads that include names, emails, or identifiers.

## ONYEN Automation

Preferred login path:

1. Complete ONYEN/OpenID login interactively in Chrome.
2. Store the resulting session material in macOS Keychain or another OS credential store.
3. Inject only the short-lived token or cookie into the daemon process at runtime.

Password automation should remain explicit opt-in and local-only. It should use realistic browser actions for simple automation blockers, but it must not write credentials to plaintext config, logs, fixtures, shell history, or commits.

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
- text, header, and JSON redaction.

Run them with:

```bash
bun run --cwd packages/promptlab test
```

## UNC Use Constraints

PromptLab access is for eligible UNC affiliates. Do not use heelcode to publish PromptLab-backed AI services for the general public. Follow UNC PromptLab terms, privacy guidance, and data classification rules. Avoid sending regulated or sensitive data that PromptLab does not permit.

## Known Limitations

- Exact authenticated payload shape still needs confirmation from a sanitized HAR.
- Tool-call preservation depends on PromptLab accepting and returning tool-call-compatible structures.
- Interactive ONYEN browser login is implemented as a capture helper, but live attempts can stop on Microsoft passkey/FIDO prompts that require user-side approval.
- The connector currently supports bearer-token/cookie runtime auth rather than persistent credential storage.
