# heelcode

heelcode is a UNC PromptLab-backed fork of opencode. It keeps the local coding-agent workflow while routing model traffic through PromptLab by way of a local OpenAI-compatible daemon.

## Status

This repository currently includes:

- the imported opencode baseline;
- a `promptlab` provider that discovers models from a local daemon;
- a new `@heelcode/promptlab` workspace package with `heelcode-promptlabd`;
- PromptLab model normalization, OpenAI-compatible model/chat endpoints, stream translation, refresh support, redaction helpers, and focused tests;
- UNC-blue TUI theming and visible heelcode branding.

## Local Flow

Start the PromptLab connector:

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

The connector currently accepts PromptLab auth material only through runtime environment:

- `PROMPTLAB_BEARER_TOKEN`
- `PROMPTLAB_COOKIE`
- `PROMPTLAB_BASE_URL`

Future ONYEN automation should use interactive browser SSO or OS credential storage. Plaintext credential config is intentionally not part of the design.

## Documentation

See [docs/promptlab-connector.md](docs/promptlab-connector.md) for architecture, setup, model discovery, testing metrics, security expectations, and known limitations.

## Upstream

heelcode is based on opencode from `https://github.com/anomalyco/opencode`.
