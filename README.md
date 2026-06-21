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

If your normal Chrome profile is already logged in to PromptLab, store the current PromptLab session in Keychain:

```bash
bun run --cwd packages/promptlab capture --store-session
```

This reads PromptLab cookies from the real Chrome profile, exchanges them for PromptLab session material, and stores only the local PromptLab session in macOS Keychain. Chrome owns ONYEN, saved passwords, MFA, and security prompts.

Start the PromptLab connector daemon:

```bash
bun run --cwd packages/promptlab serve
```

Point heelcode at it:

```bash
export HEELCODE_PROMPTLAB_URL=http://127.0.0.1:43117/v1
```

Run heelcode from source:

```bash
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
2. Run `bun run --cwd packages/promptlab capture --store-session`.
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
