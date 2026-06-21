# heelcode

heelcode is a UNC PromptLab-backed fork of opencode. It keeps the local coding-agent workflow while routing model traffic through PromptLab by way of a local OpenAI-compatible daemon.

## Status

This repository currently includes:

- the imported opencode baseline;
- a PromptLab-only default provider list and a `promptlab` provider that discovers models from a local daemon;
- a new `@heelcode/promptlab` workspace package with `heelcode-promptlabd`;
- PromptLab model normalization, OpenAI-compatible model/chat endpoints, stream translation, refresh support, redaction helpers, and focused tests;
- normal Chrome profile session capture into macOS Keychain, without blank Chrome profiles or tab shutdown;
- synthetic local-tool call bridging for explicit tool requests through the OpenAI-compatible daemon;
- UNC-blue TUI theming and visible heelcode branding.

## Local Flow

Link the local checkout once so `heelcode` is available in your shell:

```bash
ln -sf "$(pwd)/packages/opencode/bin/opencode" "$HOME/.local/bin/heelcode"
```

Then run heelcode from any project:

```bash
heelcode
```

The CLI starts `heelcode-promptlabd` on `127.0.0.1:43117`, points the PromptLab provider at `http://127.0.0.1:43117/v1`, and checks that PromptLab models are available before the TUI opens. If the stored PromptLab session is stale, heelcode opens PromptLab in the normal Google Chrome profile and captures the refreshed session after login. It does not create blank Chrome profiles or close existing Chrome tabs.

Manual fallback:

```bash
bun run --cwd packages/promptlab capture --store-session
bun run --cwd packages/promptlab serve
export HEELCODE_PROMPTLAB_URL=http://127.0.0.1:43117/v1
bun run --cwd packages/opencode dev
```

The connector exposes:

- `GET /v1/models`
- `POST /v1/chat/completions`
- `POST /v1/chat/abort`
- `GET /promptlab/active`
- `GET /promptlab/status/:conversationID`

## Credentials

Do not place ONYEN passwords, bearer tokens, cookies, or HAR files in this repository.

The preferred auth path is:

1. Log in to PromptLab in the normal Google Chrome profile.
2. Run `heelcode`; it will refresh the stored PromptLab session if needed.
3. Let `heelcode-promptlabd` read the stored PromptLab session from macOS Keychain.

Runtime environment overrides are still supported for development:

- `PROMPTLAB_BEARER_TOKEN`
- `PROMPTLAB_COOKIE`
- `PROMPTLAB_BASE_URL`

Plaintext ONYEN credential config is intentionally not part of the design.

## Documentation

See [docs/promptlab-connector.md](docs/promptlab-connector.md) for architecture, setup, model discovery, testing metrics, security expectations, and known limitations.

## Upstream

heelcode is based on opencode from `https://github.com/anomalyco/opencode`.
