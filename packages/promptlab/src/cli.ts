#!/usr/bin/env bun
import { readFile } from "node:fs/promises"
import {
  credentialStatus,
  deleteOnyenCredentials,
  deletePromptLabSession,
  saveOnyenCredentials,
  savePromptLabSession,
} from "./auth-store"
import { catalogMetrics, toOpenAIModels } from "./catalog"
import { PromptLabClient, configFromEnvOrStore, safeLogError } from "./client"
import { argValue, readSecret } from "./input"
import { redactJSON } from "./redact"
import { serve } from "./server"

const command = process.argv[2] ?? "serve"

try {
  if (command === "serve") {
    const server = serve({ config: () => configFromEnvOrStore() })
    console.log(`heelcode-promptlabd listening on http://${server.hostname}:${server.port}`)
  } else if (command === "models") {
    const client = new PromptLabClient(await configFromEnvOrStore())
    const catalog = await client.getCatalog()
    console.log(JSON.stringify({ metrics: catalogMetrics(catalog), models: toOpenAIModels(catalog).data }, null, 2))
  } else if (command === "config") {
    const client = new PromptLabClient(await configFromEnvOrStore())
    console.log(JSON.stringify(redactJSON(await client.getConfig()), null, 2))
  } else if (command === "credentials") {
    const subcommand = process.argv[3]
    const args = process.argv.slice(4)
    if (subcommand === "set") {
      const username = argValue(args, "--username") ?? process.env.PROMPTLAB_ONYEN_USERNAME
      if (!username) throw new Error("Usage: heelcode-promptlabd credentials set --username <onyen>")
      const password = await readSecret("ONYEN password: ", process.env.PROMPTLAB_ONYEN_PASSWORD)
      await saveOnyenCredentials({ username, password })
      console.log("Stored ONYEN credentials in macOS Keychain.")
    } else if (subcommand === "status") {
      console.log(JSON.stringify(await credentialStatus(), null, 2))
    } else if (subcommand === "delete") {
      await deleteOnyenCredentials()
      console.log("Deleted stored ONYEN credentials.")
    } else {
      throw new Error("Usage: heelcode-promptlabd credentials <set|status|delete>")
    }
  } else if (command === "session") {
    const subcommand = process.argv[3]
    const args = process.argv.slice(4)
    if (subcommand === "set") {
      const bearerToken =
        argValue(args, "--bearer-token") ??
        process.env.PROMPTLAB_BEARER_TOKEN ??
        (args.includes("--bearer-token-stdin") ? await readSecret("PromptLab bearer token: ") : undefined)
      const cookie =
        argValue(args, "--cookie") ??
        process.env.PROMPTLAB_COOKIE ??
        (args.includes("--cookie-stdin") ? await readSecret("PromptLab cookie: ") : undefined)
      if (!bearerToken && !cookie) {
        throw new Error("Usage: heelcode-promptlabd session set [--bearer-token-stdin|--cookie-stdin]")
      }
      await savePromptLabSession({ bearerToken, cookie })
      console.log("Stored PromptLab session material in macOS Keychain.")
    } else if (subcommand === "delete") {
      await deletePromptLabSession()
      console.log("Deleted stored PromptLab session material.")
    } else {
      throw new Error("Usage: heelcode-promptlabd session <set|delete>")
    }
  } else if (command === "redact") {
    const file = process.argv[3]
    if (!file) throw new Error("Usage: heelcode-promptlabd redact <har-or-json-file>")
    const raw = await readFile(file, "utf8")
    console.log(JSON.stringify(redactJSON(JSON.parse(raw)), null, 2))
  } else if (command === "login") {
    console.log(
      [
        "Open PromptLab in your normal Chrome profile and complete ONYEN login there.",
        "Run `heelcode-promptlabd capture --store-session` to read the real Chrome PromptLab session into Keychain.",
        "Run `heelcode-promptlabd session set --bearer-token-stdin` or `--cookie-stdin` to store a PromptLab session.",
      ].join("\n"),
    )
  } else {
    throw new Error(`Unknown command: ${command}`)
  }
} catch (error) {
  console.error(safeLogError(error))
  process.exit(1)
}
