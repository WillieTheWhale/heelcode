import { access } from "node:fs/promises"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const DEFAULT_ORIGIN = "http://127.0.0.1:43117"
const DEFAULT_URL = `${DEFAULT_ORIGIN}/v1`
const HEALTH_URL = `${DEFAULT_ORIGIN}/health`
const MODELS_URL = `${DEFAULT_URL}/models`

const sourceDir = dirname(fileURLToPath(import.meta.url))
const packagesDir = resolve(sourceDir, "../../..")
const promptlabDir = resolve(packagesDir, "promptlab")
const promptlabCli = resolve(promptlabDir, "src/cli.ts")
const promptlabCapture = resolve(promptlabDir, "src/capture.ts")
let promptLabServerProcess: ReturnType<typeof Bun.spawn> | undefined

export async function ensurePromptLabReady(args = process.argv.slice(2)): Promise<void> {
  if (!shouldBootstrap(args)) return
  process.env.HEELCODE_PROMPTLAB_URL ??= DEFAULT_URL

  await ensurePromptLabServer()
  if (await promptLabModelsReady()) return

  if (shouldAutoCapture()) {
    await capturePromptLabSession()
    if (await promptLabModelsReady()) return
  }

  throw new Error(
    [
      "PromptLab is not ready.",
      "Open PromptLab in your normal Chrome profile, complete ONYEN login, then run:",
      "  heelcode",
      "",
      "If Chrome is already logged in, refresh the stored session with:",
      "  bun run --cwd packages/promptlab capture --store-session",
    ].join("\n"),
  )
}

function shouldBootstrap(args: string[]) {
  if (process.env.HEELCODE_PROMPTLAB_AUTOSTART === "0") return false
  if (args.some((arg) => arg === "--help" || arg === "-h" || arg === "--version" || arg === "-v")) return false
  const command = args.find((arg) => !arg.startsWith("-"))
  if (!command) return true
  if (command === "run") return !args.includes("--attach")
  if (command === "models") return true
  if (command === "serve") return true
  return false
}

async function ensurePromptLabServer() {
  if (await healthy()) return
  await access(promptlabCli)
  const child = Bun.spawn([bunPath(), promptlabCli, "serve"], {
    cwd: promptlabDir,
    env: {
      ...process.env,
      HEELCODE_PROMPTLAB_URL: process.env.HEELCODE_PROMPTLAB_URL ?? DEFAULT_URL,
    },
    stdin: "ignore",
    stdout: "ignore",
    stderr: "ignore",
  })
  promptLabServerProcess = child
  child.unref?.()

  const deadline = Date.now() + 5000
  while (Date.now() < deadline) {
    if (await healthy()) return
    await delay(100)
  }

  child.kill()
  if (promptLabServerProcess === child) promptLabServerProcess = undefined
  throw new Error("Failed to start heelcode-promptlabd on http://127.0.0.1:43117.")
}

async function healthy() {
  const response = await timedFetch(HEALTH_URL, 500).catch(() => undefined)
  return response?.ok === true
}

async function promptLabModelsReady() {
  const response = await timedFetch(MODELS_URL, 4000).catch(() => undefined)
  if (!response?.ok) return false
  const body = await response.json().catch(() => undefined)
  return isRecord(body) && Array.isArray(body.data) && body.data.length > 0
}

function shouldAutoCapture() {
  if (process.env.HEELCODE_PROMPTLAB_AUTO_CAPTURE === "0") return false
  return process.stdin.isTTY || process.stderr.isTTY
}

async function capturePromptLabSession() {
  await access(promptlabCapture)
  const child = Bun.spawn([bunPath(), promptlabCapture, "--store-session"], {
    cwd: promptlabDir,
    env: {
      ...process.env,
      HEELCODE_PROMPTLAB_CAPTURE_WAIT_MS: process.env.HEELCODE_PROMPTLAB_CAPTURE_WAIT_MS ?? "45000",
    },
    stdin: "inherit",
    stdout: "inherit",
    stderr: "inherit",
  })
  const code = await child.exited
  if (code !== 0) throw new Error("PromptLab session capture failed.")
}

function bunPath() {
  return process.execPath.includes("bun") ? process.execPath : "bun"
}

function timedFetch(url: string, ms: number) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), ms)
  return fetch(url, { signal: controller.signal }).finally(() => clearTimeout(timeout))
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input)
}
