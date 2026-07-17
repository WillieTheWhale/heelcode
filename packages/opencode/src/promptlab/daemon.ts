import { access } from "node:fs/promises"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const DEFAULT_ORIGIN = "http://127.0.0.1:43117"
const DEFAULT_URL = `${DEFAULT_ORIGIN}/v1`
const PROMPTLAB_URL = "https://promptlab.lib.unc.edu/c/new"
const HEALTH_URL = `${DEFAULT_ORIGIN}/health`
const SHUTDOWN_URL = `${DEFAULT_ORIGIN}/shutdown`
const REQUIRED_PROMPTLAB_PROTOCOL = 2

const sourceDir = dirname(fileURLToPath(import.meta.url))
const packagesDir = resolve(sourceDir, "../../..")
const promptlabDir = resolve(packagesDir, "promptlab")
const promptlabCli = resolve(promptlabDir, "src/cli.ts")
const promptlabCapture = resolve(promptlabDir, "src/capture.ts")
let promptLabServerProcess: ReturnType<typeof Bun.spawn> | undefined

export async function ensurePromptLabReady(args = process.argv.slice(2)): Promise<void> {
  if (!shouldBootstrap(args)) return
  process.env.HEELCODE_PROMPTLAB_URL ??= DEFAULT_URL

  if (shouldOpenPromptLab(args)) await openPromptLabInChrome()
  await ensurePromptLabServer()
  if (await promptLabReady()) return

  if (shouldAutoCapture()) {
    await capturePromptLabSession()
    if (await promptLabReady()) return
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

export function shouldOpenPromptLab(
  args: string[],
  interactive = process.stdin.isTTY || process.stderr.isTTY,
) {
  if (!shouldBootstrap(args)) return false
  if (process.env.HEELCODE_PROMPTLAB_OPEN_BROWSER === "0") return false
  return interactive
}

export function shouldBootstrap(args: string[]) {
  if (process.env.HEELCODE_PROMPTLAB_AUTOSTART === "0") return false
  if (args.some((arg) => arg === "--help" || arg === "-h" || arg === "--version" || arg === "-v")) return false
  const command = args.find((arg) => !arg.startsWith("-"))
  if (!command) return true
  if (command === "run") return !args.includes("--attach")
  if (command === "models") return true
  if (command === "serve") return true
  if (command === "web") return true
  if (command === "generate") return true
  if (command === "acp") return true
  if (NON_BOOTSTRAP_COMMANDS.has(command)) return false
  // Unknown positionals are handled by yargs as the default TUI project path.
  // Examples: `heelcode .`, `heelcode ~/repo`, or `heelcode -m promptlab/...`.
  return true
}

const NON_BOOTSTRAP_COMMANDS = new Set([
  "completion",
  "mcp",
  "attach",
  "debug",
  "providers",
  "auth",
  "agent",
  "upgrade",
  "uninstall",
  "stats",
  "export",
  "import",
  "github",
  "pr",
  "session",
  "plugin",
  "plug",
  "db",
])

async function ensurePromptLabServer() {
  const health = await daemonHealth()
  if (health?.protocol === REQUIRED_PROMPTLAB_PROTOCOL) return
  if (health?.service === "heelcode-promptlabd") await stopStalePromptLabServer(health.pid)
  await access(promptlabCli)
  const debugOutput = process.env.HEELCODE_PROMPTLAB_DEBUG_REQUESTS === "1"
  const child = Bun.spawn([bunPath(), promptlabCli, "serve"], {
    cwd: promptlabDir,
    env: {
      ...process.env,
      HEELCODE_PROMPTLAB_URL: process.env.HEELCODE_PROMPTLAB_URL ?? DEFAULT_URL,
    },
    stdin: "ignore",
    stdout: debugOutput ? "inherit" : "ignore",
    stderr: debugOutput ? "inherit" : "ignore",
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
  return (await daemonHealth())?.protocol === REQUIRED_PROMPTLAB_PROTOCOL
}

async function daemonHealth() {
  const response = await timedFetch(HEALTH_URL, 500).catch(() => undefined)
  if (!response?.ok) return
  const body: unknown = await response.json().catch(() => undefined)
  if (!isRecord(body) || body.ok !== true || typeof body.service !== "string") return
  return {
    service: body.service,
    protocol: typeof body.protocol === "number" ? body.protocol : undefined,
    pid: typeof body.pid === "number" && Number.isInteger(body.pid) && body.pid > 1 ? body.pid : undefined,
  }
}

async function stopStalePromptLabServer(pid: number | undefined) {
  await fetch(SHUTDOWN_URL, { method: "POST" }).catch(() => undefined)
  if (await waitForPromptLabExit(1000)) return
  const owner = pid ?? (process.platform === "darwin" ? await promptLabPortOwner() : undefined)
  if (owner) process.kill(owner, "SIGTERM")
  if (await waitForPromptLabExit(2000)) return
  throw new Error("An outdated heelcode-promptlabd is still listening on http://127.0.0.1:43117.")
}

async function waitForPromptLabExit(timeout: number) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (!(await timedFetch(HEALTH_URL, 200).catch(() => undefined))) return true
    await delay(50)
  }
  return false
}

async function promptLabPortOwner() {
  const child = Bun.spawn(["lsof", "-nP", "-tiTCP:43117", "-sTCP:LISTEN"], {
    stdin: "ignore",
    stdout: "pipe",
    stderr: "ignore",
  })
  const output = await new Response(child.stdout).text()
  if ((await child.exited) !== 0) return
  const pid = Number(output.trim().split(/\s+/)[0])
  return Number.isInteger(pid) && pid > 1 ? pid : undefined
}

export async function promptLabReady(origin = DEFAULT_ORIGIN) {
  const active = await timedFetch(`${origin}/promptlab/active`, 4000).catch(() => undefined)
  if (!active?.ok) return false
  const response = await timedFetch(`${origin}/v1/models`, 4000).catch(() => undefined)
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
  if (code !== 0)
    throw new Error("PromptLab login was not completed. Sign in to PromptLab in normal Chrome, then run HeelCode again.")
}

async function openPromptLabInChrome() {
  await Bun.spawn(["open", "-a", "Google Chrome", PROMPTLAB_URL], {
    stdout: "ignore",
    stderr: "ignore",
  }).exited.catch(() => {})
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
