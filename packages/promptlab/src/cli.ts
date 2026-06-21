#!/usr/bin/env bun
import { readFile } from "node:fs/promises"
import { catalogMetrics, toOpenAIModels } from "./catalog"
import { PromptLabClient, configFromEnv, safeLogError } from "./client"
import { redactJSON } from "./redact"
import { serve } from "./server"

const command = process.argv[2] ?? "serve"

try {
  if (command === "serve") {
    const server = serve()
    console.log(`heelcode-promptlabd listening on http://${server.hostname}:${server.port}`)
  } else if (command === "models") {
    const client = new PromptLabClient(configFromEnv())
    const catalog = await client.getCatalog()
    console.log(JSON.stringify({ metrics: catalogMetrics(catalog), models: toOpenAIModels(catalog).data }, null, 2))
  } else if (command === "config") {
    const client = new PromptLabClient(configFromEnv())
    console.log(JSON.stringify(redactJSON(await client.getConfig()), null, 2))
  } else if (command === "redact") {
    const file = process.argv[3]
    if (!file) throw new Error("Usage: heelcode-promptlabd redact <har-or-json-file>")
    const raw = await readFile(file, "utf8")
    console.log(JSON.stringify(redactJSON(JSON.parse(raw)), null, 2))
  } else if (command === "login") {
    console.log(
      [
        "Interactive ONYEN login is intentionally not implemented as plaintext password storage.",
        "Use PromptLab in Chrome, export a short-lived bearer token/cookie into the OS credential store,",
        "then launch with PROMPTLAB_BEARER_TOKEN or PROMPTLAB_COOKIE injected at runtime.",
      ].join("\n"),
    )
  } else {
    throw new Error(`Unknown command: ${command}`)
  }
} catch (error) {
  console.error(safeLogError(error))
  process.exit(1)
}
