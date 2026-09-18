import path from "node:path"

// Public research artifacts never need server cookies or authentication/header material.
const file = process.argv[2]
if (!file) throw new Error("Usage: bun script/sanitize-evidence.ts JSONL_FILE")
const text = await Bun.file(file).text()
const strip = (key: string, value: unknown) => /^(requestHeaders|responseHeaders|authorization|cookie|set-cookie|access_token|refresh_token|apiKey)$/i.test(key) ? "[redacted]" : value
const lines = text.trim().split("\n").filter(Boolean).map(line => JSON.stringify(JSON.parse(line), strip)).join("\n")
await Bun.write(path.resolve(file), lines + "\n")
